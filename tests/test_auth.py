"""Optional Google sign-in: tokens, accounts, abuse blocking, and the HTTP layer."""

import jwt
import pytest

from backend.app import RateLimits, create_app
from backend.auth import (
    ACCESS_TTL_S,
    BLOCK_STEPS_S,
    REFRESH_TTL_S,
    AbuseGuard,
    Account,
    AuthError,
    AuthService,
    FirestoreAccountStore,
    InMemoryAccountStore,
    TokenService,
)
from backend.geo import offset_point
from backend.models import ParkingSegment, ZoneType
from backend.session import SessionStore

SECRET = "x" * 40
ALICE = "google-sub-alice"
OWNER = "google-sub-owner"


class Clock:
    def __init__(self, now=1_000_000.0):
        self.now = now

    def __call__(self):
        return self.now


def google_claims(sub=ALICE, **extra):
    return {"sub": sub, "email": f"{sub}@example.com", "email_verified": True, "name": "Test User", **extra}


def make_service(clock=None, owners=frozenset(), verifier=None, store=None):
    clock = clock or Clock()
    return AuthService(
        store=store or InMemoryAccountStore(),
        tokens=TokenService(SECRET, clock=clock),
        verify_google=verifier or (lambda token: google_claims(sub=token)),  # the "id token" is the sub
        owner_subs=owners,
        clock=clock,
    ), clock


# ------------------------------------------------------------ tokens

def test_access_token_round_trips_and_expires():
    clock = Clock()
    tokens = TokenService(SECRET, clock=clock)
    token, expires_in = tokens.issue_access(ALICE, "user")
    assert expires_in == ACCESS_TTL_S
    assert tokens.verify_access(token)["sub"] == ALICE

    clock.now += ACCESS_TTL_S + 1
    with pytest.raises(AuthError) as err:
        tokens.verify_access(token)
    assert err.value.code == "token_expired"


def test_tampered_foreign_and_unsigned_tokens_are_rejected():
    tokens = TokenService(SECRET)
    good, _ = tokens.issue_access(ALICE, "user")
    other, _ = TokenService("y" * 40).issue_access(ALICE, "user")
    unsigned = jwt.encode({"sub": ALICE, "exp": 4_000_000_000, "iat": 1}, key=None, algorithm="none")
    for bad in (good[:-3] + "abc", other, unsigned, "not-a-token", ""):
        with pytest.raises(AuthError) as err:
            tokens.verify_access(bad)
        assert err.value.code == "invalid_token"


def test_secret_must_be_long_enough():
    with pytest.raises(ValueError):
        TokenService("short")


# --------------------------------------------------------- sign-in flows

def test_sign_in_creates_an_account_and_issues_tokens():
    service, _ = make_service()
    session = service.sign_in_with_google(ALICE)
    assert session.account.email == f"{ALICE}@example.com"
    assert service.tokens.verify_access(session.access_token)["sub"] == ALICE
    assert service.store.get(ALICE) is not None


def test_unverified_email_and_bad_google_tokens_are_refused():
    def bad(token):
        raise AuthError("invalid_google_token", "nope")

    service, _ = make_service(verifier=lambda t: google_claims(email_verified=False))
    with pytest.raises(AuthError) as err:
        service.sign_in_with_google("t")
    assert err.value.code == "email_not_verified" and err.value.status == 403

    service, _ = make_service(verifier=bad)
    with pytest.raises(AuthError) as err:
        service.sign_in_with_google("t")
    assert err.value.code == "invalid_google_token"


def test_refresh_rotates_and_the_old_token_stops_working():
    service, _ = make_service()
    first = service.sign_in_with_google(ALICE)
    second = service.refresh(first.refresh_token)
    assert second.refresh_token != first.refresh_token
    with pytest.raises(AuthError) as err:
        service.refresh(first.refresh_token)  # replaying a used token
    assert err.value.code == "invalid_refresh_token"
    assert service.refresh(second.refresh_token).account.sub == ALICE


def test_refresh_tokens_expire_and_can_be_revoked():
    service, clock = make_service()
    session = service.sign_in_with_google(ALICE)
    clock.now += REFRESH_TTL_S + 1
    with pytest.raises(AuthError):
        service.refresh(session.refresh_token)

    session = service.sign_in_with_google(ALICE)
    service.sign_out(session.refresh_token)
    with pytest.raises(AuthError):
        service.refresh(session.refresh_token)


def test_blocked_accounts_cannot_sign_in_or_refresh_but_owners_can():
    service, clock = make_service(owners=frozenset({OWNER}))
    session = service.sign_in_with_google(ALICE)
    owner_session = service.sign_in_with_google(OWNER)

    for sub in (ALICE, OWNER):
        account = service.store.get(sub)
        account.blocked = True
        account.blocked_until = None
        service.store.save(account)

    with pytest.raises(AuthError) as err:
        service.refresh(session.refresh_token)
    assert err.value.code == "account_blocked" and err.value.status == 403
    with pytest.raises(AuthError):
        service.sign_in_with_google(ALICE)
    assert service.refresh(owner_session.refresh_token).account.role == "owner"


def test_deleting_an_account_removes_it_and_its_tokens():
    service, _ = make_service()
    session = service.sign_in_with_google(ALICE)
    service.delete_account(ALICE)
    assert service.store.get(ALICE) is None
    with pytest.raises(AuthError):
        service.refresh(session.refresh_token)


# -------------------------------------------------------------- guard

def guard_for(service, per_strike=3, window_s=60):
    return AbuseGuard(service, violations_per_strike=per_strike, window_s=window_s)


def test_repeated_violations_block_with_escalating_durations():
    service, clock = make_service()
    service.sign_in_with_google(ALICE)
    guard = guard_for(service)

    durations = []
    for _ in range(len(BLOCK_STEPS_S)):
        blocked = None
        for _ in range(3):
            blocked = guard.record_violation(ALICE)
        assert blocked is not None
        durations.append(None if blocked.blocked_until is None else blocked.blocked_until - clock.now)
    assert durations == [float(d) if d is not None else None for d in BLOCK_STEPS_S]


def test_violations_spread_over_time_do_not_add_up():
    service, clock = make_service()
    service.sign_in_with_google(ALICE)
    guard = guard_for(service, per_strike=3, window_s=60)
    for _ in range(10):
        assert guard.record_violation(ALICE) is None
        clock.now += 40  # never three inside one 60 s window
    assert not service.store.get(ALICE).blocked


def test_owners_are_never_blocked_and_blocking_revokes_refresh_tokens():
    service, _ = make_service(owners=frozenset({OWNER}))
    service.sign_in_with_google(OWNER)
    guard = guard_for(service)
    assert all(guard.record_violation(OWNER) is None for _ in range(20))

    session = service.sign_in_with_google(ALICE)
    for _ in range(3):
        guard.record_violation(ALICE)
    with pytest.raises(AuthError):
        service.refresh(session.refresh_token)


def test_a_block_expires():
    service, clock = make_service()
    service.sign_in_with_google(ALICE)
    guard = guard_for(service)
    for _ in range(3):
        guard.record_violation(ALICE)
    account = service.store.get(ALICE)
    assert account.is_blocked(clock.now)
    clock.now += 60 * 60 + 1
    assert not account.is_blocked(clock.now)
    service.sign_in_with_google(ALICE)  # allowed again


# ------------------------------------------------- Firestore adapter (fake)

class FakeSnap:
    def __init__(self, ref, data):
        self.reference = ref
        self._data = data
        self.exists = data is not None

    def to_dict(self):
        return dict(self._data) if self._data is not None else None


class FakeRef:
    def __init__(self, docs, key):
        self._docs, self._key = docs, key

    def get(self):
        return FakeSnap(self, self._docs.get(self._key))

    def set(self, data):
        self._docs[self._key] = dict(data)

    def delete(self):
        self._docs.pop(self._key, None)


class FakeQuery:
    def __init__(self, docs, field, value):
        self._docs, self._field, self._value = docs, field, value

    def stream(self):
        return [FakeSnap(FakeRef(self._docs, k), v) for k, v in list(self._docs.items()) if v.get(self._field) == self._value]


class FakeCollection:
    def __init__(self, docs):
        self._docs = docs

    def document(self, key):
        return FakeRef(self._docs, key)

    def where(self, field, op, value):
        assert op == "=="
        return FakeQuery(self._docs, field, value)


class FakeFirestore:
    def __init__(self):
        self.data = {}

    def collection(self, name):
        return FakeCollection(self.data.setdefault(name, {}))


def test_firestore_store_round_trips_accounts_and_refresh_tokens():
    db = FakeFirestore()
    store = FirestoreAccountStore(client=db)
    account = Account(sub=ALICE, email="a@example.com", role="owner", strikes=2, blocked=True, blocked_until=5.0)
    store.save(account)
    assert store.get(ALICE) == account
    assert store.get("missing") is None

    store.put_refresh("hash1", ALICE, 99.0)
    store.put_refresh("hash2", ALICE, 99.0)
    store.put_refresh("hash3", "someone-else", 99.0)
    assert store.take_refresh("hash1") == (ALICE, 99.0)
    assert store.take_refresh("hash1") is None  # single use
    store.delete_refresh_for(ALICE)
    assert store.take_refresh("hash2") is None
    assert store.take_refresh("hash3") == ("someone-else", 99.0)

    store.delete(ALICE)
    assert store.get(ALICE) is None


def test_the_full_service_works_on_the_firestore_store():
    service, _ = make_service(store=FirestoreAccountStore(client=FakeFirestore()))
    session = service.sign_in_with_google(ALICE)
    assert service.refresh(session.refresh_token).account.sub == ALICE


# ------------------------------------------------------------ HTTP layer

ORIGIN_LAT, ORIGIN_LON = 47.3703, 8.5386


def make_client(limits=None, owners=frozenset(), with_auth=True):
    lat, lon = offset_point(ORIGIN_LAT, ORIGIN_LON, 100, 0)
    segment = ParkingSegment(id="A", lat=lat, lon=lon, zone_type=ZoneType.BLUE,
                             address_label="A", estimated_capacity=1, max_duration_minutes=None)
    service, clock = make_service(owners=owners) if with_auth else (None, Clock())
    app = create_app(store=SessionStore([segment]), rate_limits=limits or RateLimits(), auth=service)
    app.config.update(TESTING=True)
    return app.test_client(), service, clock


def sign_in(client, sub=ALICE, headers=None):
    response = client.post("/api/auth/google", json={"id_token": sub}, headers=headers or {})
    assert response.status_code == 200, response.get_json()
    body = response.get_json()
    return body, {"Authorization": f"Bearer {body['access_token']}"}


def new_session(client, headers=None):
    return client.post("/api/session", json={"lat": ORIGIN_LAT, "lon": ORIGIN_LON}, headers=headers or {})


def test_sign_in_and_me_over_http():
    client, _, _ = make_client()
    body, auth = sign_in(client)
    assert body["account"]["email"] == f"{ALICE}@example.com"
    me = client.get("/api/me", headers=auth).get_json()
    assert me["sub"] == ALICE and me["role"] == "user"
    assert client.get("/api/me").status_code == 401


def test_anonymous_use_still_works_without_signing_in():
    client, _, _ = make_client()
    assert new_session(client).status_code == 201


def test_everything_stays_anonymous_when_sign_in_is_not_configured():
    client, _, _ = make_client(with_auth=False)
    assert client.post("/api/auth/google", json={"id_token": "x"}).status_code == 503
    # A stray token is ignored rather than breaking the call.
    assert new_session(client, {"Authorization": "Bearer junk"}).status_code == 201


def test_expired_tampered_and_unknown_tokens_get_401():
    client, service, clock = make_client()
    body, auth = sign_in(client)

    clock.now += ACCESS_TTL_S + 1
    expired = client.get("/api/me", headers=auth)
    assert expired.status_code == 401 and expired.get_json()["code"] == "token_expired"

    assert client.get("/api/me", headers={"Authorization": "Bearer junk"}).status_code == 401

    clock.now = 1_000_000.0
    client.delete("/api/me", headers=auth)  # account gone, token still cryptographically valid
    assert client.get("/api/me", headers=auth).status_code == 401


def test_refresh_over_http_returns_a_fresh_access_token():
    client, _, _ = make_client()
    body, _ = sign_in(client)
    refreshed = client.post("/api/auth/refresh", json={"refresh_token": body["refresh_token"]})
    assert refreshed.status_code == 200
    assert client.post("/api/auth/refresh", json={"refresh_token": body["refresh_token"]}).status_code == 401
    assert client.post("/api/auth/logout", json={"refresh_token": refreshed.get_json()["refresh_token"]}).status_code == 204


def test_signed_in_callers_get_higher_limits_counted_per_account_not_per_address():
    limits = RateLimits(create_session="1 per minute", create_session_account="3 per minute")
    client, _, _ = make_client(limits)

    assert new_session(client).status_code == 201
    assert new_session(client).status_code == 429  # anonymous: 1 per minute

    _, auth = sign_in(client)
    from_a = {**auth, "X-Forwarded-For": "203.0.113.1"}
    from_b = {**auth, "X-Forwarded-For": "203.0.113.2"}
    assert [new_session(client, h).status_code for h in (from_a, from_b, from_a)] == [201, 201, 201]
    assert new_session(client, from_b).status_code == 429  # the account's 3 are used up, whatever the address


def test_owner_accounts_are_exempt_from_rate_limits():
    limits = RateLimits(create_session="1 per minute", create_session_account="1 per minute")
    client, _, _ = make_client(limits, owners=frozenset({OWNER}))
    _, auth = sign_in(client, OWNER)
    assert all(new_session(client, auth).status_code == 201 for _ in range(10))


def test_a_blocked_account_is_refused_on_every_call():
    client, service, _ = make_client()
    _, auth = sign_in(client)
    account = service.store.get(ALICE)
    account.blocked, account.blocked_until = True, None
    service.store.save(account)
    service._cache.clear()

    blocked = new_session(client, auth)
    assert blocked.status_code == 403 and blocked.get_json()["code"] == "account_blocked"
    assert new_session(client).status_code == 201  # anonymous callers are unaffected


def test_hammering_the_api_while_signed_in_ends_in_an_automatic_block():
    limits = RateLimits(create_session_account="1 per minute")
    client, service, _ = make_client(limits)
    _, auth = sign_in(client)

    # 1 allowed, then breaches until the guard's threshold (30) blocks the account.
    statuses = [new_session(client, auth).status_code for _ in range(40)]
    assert statuses[0] == 201
    assert 429 in statuses and 403 in statuses
    assert service.store.get(ALICE).blocked
    assert new_session(client, auth).status_code == 403


def test_hammering_never_blocks_an_owner():
    limits = RateLimits(create_session_account="1 per minute")
    client, service, _ = make_client(limits, owners=frozenset({OWNER}))
    _, auth = sign_in(client, OWNER)
    assert all(new_session(client, auth).status_code == 201 for _ in range(40))
    assert not service.store.get(OWNER).blocked


def test_delete_account_over_http():
    client, service, _ = make_client()
    _, auth = sign_in(client)
    assert client.delete("/api/me", headers=auth).status_code == 204
    assert service.store.get(ALICE) is None


def test_sign_in_endpoints_are_rate_limited_per_address():
    client, _, _ = make_client(RateLimits(auth="2 per minute"))
    codes = [client.post("/api/auth/google", json={"id_token": ALICE}).status_code for _ in range(3)]
    assert codes == [200, 200, 429]


def test_cors_allows_the_authorization_header():
    client, _, _ = make_client()
    response = client.post("/api/session", json={"lat": ORIGIN_LAT, "lon": ORIGIN_LON},
                           headers={"Origin": "https://lammertsma.dev"})
    assert "Authorization" in response.headers["Access-Control-Allow-Headers"]
    assert "DELETE" in response.headers["Access-Control-Allow-Methods"]
