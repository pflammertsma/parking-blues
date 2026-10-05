"""Optional Google sign-in: accounts, tokens and abuse blocking.

Anonymous use stays possible; signing in only raises rate limits and lets abuse
be traced to an account so it can be blocked (see TODO.md). Plain objects with
no Flask in here, so the rules can be unit tested without a server:

- ``GoogleVerifier``   checks a Google ID token and returns its claims.
- ``TokenService``     issues/verifies our own short-lived access JWTs and
                       opaque, rotating refresh tokens (stored hashed).
- ``AccountStore``     accounts, block status and refresh tokens; in memory for
                       tests and local dev, Firestore in production.
- ``AuthService``      the sign-in / refresh / sign-out / delete rules.
- ``AbuseGuard``       turns repeated rate-limit violations by an account into an
                       escalating block. Owner accounts are never blocked.
"""

from __future__ import annotations

import hashlib
import os
import secrets
import time
from collections import defaultdict, deque
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Protocol

import jwt

ACCESS_TTL_S = 15 * 60
REFRESH_TTL_S = 30 * 24 * 60 * 60

# Violations (429s) by one account inside the window before it is struck.
VIOLATIONS_PER_STRIKE = 30
VIOLATION_WINDOW_S = 10 * 60
# Block length per strike: 1 hour, 1 day, 1 week, then permanent (None).
BLOCK_STEPS_S: list[int | None] = [60 * 60, 24 * 60 * 60, 7 * 24 * 60 * 60, None]

ROLE_USER = "user"
ROLE_OWNER = "owner"


class AuthError(Exception):
    """Sign-in or token problem. ``code`` is machine readable for clients."""

    def __init__(self, code: str, message: str, status: int = 401):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status


@dataclass
class Account:
    sub: str  # Google's stable account id; never identify accounts by email alone
    email: str = ""
    name: str = ""
    role: str = ROLE_USER
    created_at: float = 0.0
    last_seen_at: float = 0.0
    strikes: int = 0
    blocked_until: float | None = None  # epoch seconds; ignored unless ``blocked``
    blocked: bool = False
    block_reason: str = ""

    def is_blocked(self, now: float) -> bool:
        if not self.blocked:
            return False
        return self.blocked_until is None or now < self.blocked_until


# ---------------------------------------------------------------- storage

class AccountStore(Protocol):
    def get(self, sub: str) -> Account | None: ...
    def save(self, account: Account) -> None: ...
    def delete(self, sub: str) -> None: ...
    def put_refresh(self, token_hash: str, sub: str, expires_at: float) -> None: ...
    def take_refresh(self, token_hash: str) -> tuple[str, float] | None:
        """Atomically remove and return (sub, expires_at), or None if unknown."""
    def delete_refresh_for(self, sub: str) -> None: ...


class InMemoryAccountStore:
    """For tests and local development. Loses everything on restart."""

    def __init__(self) -> None:
        self._accounts: dict[str, Account] = {}
        self._refresh: dict[str, tuple[str, float]] = {}

    def get(self, sub: str) -> Account | None:
        account = self._accounts.get(sub)
        return None if account is None else Account(**vars(account))

    def save(self, account: Account) -> None:
        self._accounts[account.sub] = Account(**vars(account))

    def delete(self, sub: str) -> None:
        self._accounts.pop(sub, None)

    def put_refresh(self, token_hash: str, sub: str, expires_at: float) -> None:
        self._refresh[token_hash] = (sub, expires_at)

    def take_refresh(self, token_hash: str) -> tuple[str, float] | None:
        return self._refresh.pop(token_hash, None)

    def delete_refresh_for(self, sub: str) -> None:
        for h in [h for h, (s, _) in self._refresh.items() if s == sub]:
            del self._refresh[h]


class FirestoreAccountStore:
    """Accounts in ``accounts/{sub}``; refresh tokens in ``refresh_tokens/{hash}``.

    Only token *hashes* are stored, so a database leak cannot be replayed. The
    database should live in europe-west6 (Zurich); see deploy/auth-setup.md.
    """

    def __init__(self, client=None, project: str | None = None) -> None:
        if client is None:
            from google.cloud import firestore

            client = firestore.Client(project=project)
        self._db = client

    def _accounts(self):
        return self._db.collection("accounts")

    def _tokens(self):
        return self._db.collection("refresh_tokens")

    def get(self, sub: str) -> Account | None:
        snap = self._accounts().document(sub).get()
        if not snap.exists:
            return None
        data = snap.to_dict() or {}
        fields = {k: v for k, v in data.items() if k in Account.__dataclass_fields__ and k != "sub"}
        return Account(sub=sub, **fields)

    def save(self, account: Account) -> None:
        self._accounts().document(account.sub).set(vars(account))

    def delete(self, sub: str) -> None:
        self._accounts().document(sub).delete()

    def put_refresh(self, token_hash: str, sub: str, expires_at: float) -> None:
        self._tokens().document(token_hash).set({"sub": sub, "expires_at": expires_at})

    def take_refresh(self, token_hash: str) -> tuple[str, float] | None:
        ref = self._tokens().document(token_hash)
        snap = ref.get()
        if not snap.exists:
            return None
        data = snap.to_dict() or {}
        ref.delete()
        return data["sub"], float(data["expires_at"])

    def delete_refresh_for(self, sub: str) -> None:
        for snap in self._tokens().where("sub", "==", sub).stream():
            snap.reference.delete()


# ----------------------------------------------------------------- google

GoogleVerifier = Callable[[str], dict]


def google_id_token_verifier(web_client_id: str) -> GoogleVerifier:
    """Verifies Google ID tokens whose audience is our web client id."""

    def verify(id_token: str) -> dict:
        from google.auth.transport import requests as google_requests
        from google.oauth2 import id_token as google_id_token

        try:
            return google_id_token.verify_oauth2_token(
                id_token, google_requests.Request(), audience=web_client_id
            )
        except ValueError as exc:  # bad signature, expired, wrong audience, ...
            raise AuthError("invalid_google_token", "Google sign-in could not be verified") from exc

    return verify


# ----------------------------------------------------------------- tokens

class TokenService:
    def __init__(self, secret: str, clock: Callable[[], float] = time.time):
        if len(secret) < 32:
            raise ValueError("AUTH_JWT_SECRET must be at least 32 characters")
        self._secret = secret
        self._clock = clock

    def issue_access(self, sub: str, role: str) -> tuple[str, int]:
        now = int(self._clock())
        token = jwt.encode(
            {"sub": sub, "role": role, "iat": now, "exp": now + ACCESS_TTL_S, "typ": "access"},
            self._secret,
            algorithm="HS256",
        )
        return token, ACCESS_TTL_S

    def verify_access(self, token: str) -> dict:
        try:
            claims = jwt.decode(
                token,
                self._secret,
                algorithms=["HS256"],  # pinned: never trust the token's own "alg"
                # Expiry is checked below against our (injectable) clock.
                options={"require": ["exp", "iat", "sub"], "verify_exp": False, "verify_iat": False},
            )
        except jwt.InvalidTokenError as exc:
            raise AuthError("invalid_token", "Invalid access token") from exc
        if claims.get("typ") != "access":
            raise AuthError("invalid_token", "Invalid access token")
        if self._clock() >= claims["exp"]:
            raise AuthError("token_expired", "Access token expired")
        return claims

    @staticmethod
    def new_refresh() -> tuple[str, str]:
        token = secrets.token_urlsafe(32)
        return token, TokenService.hash_refresh(token)

    @staticmethod
    def hash_refresh(token: str) -> str:
        return hashlib.sha256(token.encode()).hexdigest()


# ---------------------------------------------------------------- service

@dataclass
class Session:
    access_token: str
    expires_in: int
    refresh_token: str
    account: Account


@dataclass
class AuthService:
    store: AccountStore
    tokens: TokenService
    verify_google: GoogleVerifier
    owner_subs: frozenset[str] = frozenset()
    clock: Callable[[], float] = time.time
    # Account lookups are cached briefly so a request does not read the database
    # each time; blocking updates the cache immediately.
    cache_ttl_s: float = 30.0
    _cache: dict[str, tuple[float, Account | None]] = field(default_factory=dict)

    # -- accounts -----------------------------------------------------------
    def account(self, sub: str) -> Account | None:
        now = self.clock()
        hit = self._cache.get(sub)
        if hit and now - hit[0] < self.cache_ttl_s:
            return hit[1]
        account = self.store.get(sub)
        self._cache[sub] = (now, account)
        return account

    def _remember(self, account: Account) -> None:
        self._cache[account.sub] = (self.clock(), account)

    def role_for(self, sub: str) -> str:
        return ROLE_OWNER if sub in self.owner_subs else ROLE_USER

    # -- flows --------------------------------------------------------------
    def sign_in_with_google(self, id_token: str) -> Session:
        claims = self.verify_google(id_token)
        sub = claims.get("sub")
        if not sub:
            raise AuthError("invalid_google_token", "Google sign-in could not be verified")
        if not claims.get("email_verified", False):
            raise AuthError("email_not_verified", "The Google account's email is not verified", 403)

        now = self.clock()
        account = self.store.get(sub) or Account(sub=sub, created_at=now)
        account.email = claims.get("email", account.email)
        account.name = claims.get("name", account.name)
        account.role = self.role_for(sub)
        account.last_seen_at = now
        self._refuse_if_blocked(account)
        self.store.save(account)
        self._remember(account)
        return self._start_session(account)

    def refresh(self, refresh_token: str) -> Session:
        taken = self.store.take_refresh(TokenService.hash_refresh(refresh_token))
        if taken is None:
            raise AuthError("invalid_refresh_token", "Sign in again")
        sub, expires_at = taken
        now = self.clock()
        if now >= expires_at:
            raise AuthError("invalid_refresh_token", "Sign in again")
        account = self.store.get(sub)
        if account is None:
            raise AuthError("invalid_refresh_token", "Sign in again")
        self._refuse_if_blocked(account)
        account.last_seen_at = now
        account.role = self.role_for(sub)
        self.store.save(account)
        self._remember(account)
        return self._start_session(account)

    def sign_out(self, refresh_token: str) -> None:
        self.store.take_refresh(TokenService.hash_refresh(refresh_token))

    def delete_account(self, sub: str) -> None:
        self.store.delete_refresh_for(sub)
        self.store.delete(sub)
        self._cache.pop(sub, None)

    # -- helpers ------------------------------------------------------------
    def _refuse_if_blocked(self, account: Account) -> None:
        if account.role != ROLE_OWNER and account.is_blocked(self.clock()):
            raise AuthError("account_blocked", "This account is blocked", 403)

    def _start_session(self, account: Account) -> Session:
        access, expires_in = self.tokens.issue_access(account.sub, account.role)
        refresh, refresh_hash = TokenService.new_refresh()
        self.store.put_refresh(refresh_hash, account.sub, self.clock() + REFRESH_TTL_S)
        return Session(access, expires_in, refresh, account)


# ------------------------------------------------------------ abuse guard

@dataclass
class AbuseGuard:
    """Escalating automatic blocks for accounts that keep hitting rate limits."""

    service: AuthService
    violations_per_strike: int = VIOLATIONS_PER_STRIKE
    window_s: float = VIOLATION_WINDOW_S
    steps_s: list[int | None] = field(default_factory=lambda: list(BLOCK_STEPS_S))
    _recent: dict[str, deque] = field(default_factory=lambda: defaultdict(deque))

    def record_violation(self, sub: str) -> Account | None:
        """Count one rate-limit breach. Returns the account if it was just blocked."""
        if sub in self.service.owner_subs:
            return None
        now = self.service.clock()
        recent = self._recent[sub]
        recent.append(now)
        while recent and now - recent[0] > self.window_s:
            recent.popleft()
        if len(recent) < self.violations_per_strike:
            return None

        account = self.service.store.get(sub)
        if account is None:
            return None
        step = min(account.strikes, len(self.steps_s) - 1)
        duration = self.steps_s[step]
        account.strikes += 1
        account.blocked = True
        account.blocked_until = None if duration is None else now + duration
        account.block_reason = (
            f"Exceeded rate limits {self.violations_per_strike}x in {int(self.window_s // 60)} min"
        )
        self.service.store.save(account)
        self.service.store.delete_refresh_for(sub)  # force a re-check at next sign-in
        self.service._remember(account)
        recent.clear()
        return account


def auth_from_env() -> AuthService | None:
    """Sign-in is on only when both a Google web client id and a signing secret
    are configured; otherwise the server stays fully anonymous, as before.

    GOOGLE_WEB_CLIENT_ID  the OAuth *web* client id (the audience of ID tokens)
    AUTH_JWT_SECRET       32+ random characters (Secret Manager in production)
    AUTH_STORE            "firestore" (default) or "memory" (local development)
    OWNER_SUBS            comma-separated Google account ids exempt from limits/blocks
    """
    web_client_id = os.environ.get("GOOGLE_WEB_CLIENT_ID")
    secret = os.environ.get("AUTH_JWT_SECRET")
    if not (web_client_id and secret):
        return None
    if os.environ.get("AUTH_STORE", "firestore") == "memory":
        store: AccountStore = InMemoryAccountStore()
    else:
        store = FirestoreAccountStore(project=os.environ.get("GOOGLE_CLOUD_PROJECT"))
    owners = frozenset(s.strip() for s in os.environ.get("OWNER_SUBS", "").split(",") if s.strip())
    return AuthService(store, TokenService(secret), google_id_token_verifier(web_client_id), owners)
