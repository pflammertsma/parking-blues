const $ = (id) => document.getElementById(id);

// When this page is served as static files from lammertsma.dev (e.g.
// /projects/parking-blues/), the Flask API isn't on the same origin --
// it's reached via api.parking-blues.lammertsma.dev instead (see README
// section 11). Anywhere else (this app's own Cloud Run domain, or local
// dev via `python -m backend.app`), the API is same-origin as usual.
const API_BASE = window.location.hostname.endsWith("lammertsma.dev")
  ? "https://api.parking-blues.lammertsma.dev"
  : "";

// Zurich Hauptbahnhof: what the page shows until (or unless) it learns where
// the visitor is, so it is never empty.
const ZURICH_CENTER = { lat: 47.3779, lng: 8.5402 };
// A generous box around the city: the parking data covers nothing outside it.
const ZURICH_BOUNDS = { south: 47.30, north: 47.45, west: 8.42, east: 8.66 };

const map = L.map("map", { tap: false }).setView([ZURICH_CENTER.lat, ZURICH_CENTER.lng], 16);
// CartoDB's "Positron" basemap: a light, minimal OSM-derived style that
// keeps streets/labels/buildings/rail but drops the POI icon clutter
// (restaurants, shops, etc.) of the default OSM tiles. CARTO requires an API
// key, which must not ship in a public page, so tiles come through our own
// server (backend/tiles.py), which adds the key.
L.tileLayer(
  `${API_BASE}/api/tiles/light_all/{z}/{x}/{y}{r}.png`,
  {
    attribution:
      "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors &copy; <a href=\"https://carto.com/attributions\">CARTO</a>",
    maxZoom: 19,
  }
).addTo(map);

// The destination: where the visitor wants to be. Everything shown is
// relative to it; moving it (drag, tap on the map, address search, "my
// location") is the one way to change the answer.
const destinationMarker = L.marker([ZURICH_CENTER.lat, ZURICH_CENTER.lng], {
  draggable: true,
  keyboard: false,
}).addTo(map).bindTooltip("Destination", {
  permanent: true,
  direction: "top",
  // Leaflet's default pin has its tooltip anchor 4px right of the pin's centre
  // and low on the head; shift the label to sit centred just above the pin.
  offset: [-16, -14],
});
const areaLayer = L.layerGroup().addTo(map);

async function api(path, options = {}) {
  const send = (token) =>
    fetch(API_BASE + path, {
      ...options,
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
    });
  const token = await currentAccessToken();
  let response = await send(token);
  if (response.status === 401 && token) {
    // The server rejected our access token: renew it once, or carry on anonymously.
    response = await send(await refreshAccessToken());
  }
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 429) {
      const wait = response.headers.get("Retry-After");
      throw new Error(`Too many requests, try again${wait ? ` in ${wait} s` : " shortly"}.`);
    }
    throw new Error(data.error || `request to ${path} failed`);
  }
  return data;
}

// --- Optional Google sign-in -------------------------------------------------
// Anonymous use is the default and always works. Signing in only raises request
// limits. The OAuth *web* client ID is public by design (it is in every sign-in
// page's source); the Google script itself is loaded only when the visitor
// opens the sign-in panel, so merely browsing contacts nobody extra. Leave the
// ID empty to hide sign-in. Setup: deploy/auth-setup.md.
const GOOGLE_WEB_CLIENT_ID =
  "794638973209-r1ukb3sac08eeomf4kjve5aj8bkbga52.apps.googleusercontent.com";
const AUTH_STORAGE_KEY = "parking-blues-auth";
const ACCESS_TOKEN_MARGIN_MS = 60 * 1000;

// { refreshToken, account } persists across visits; the short-lived access
// token lives only in memory and is re-issued from the refresh token.
let auth = readStoredAuth();
let accessToken = null;
let accessTokenExpiresAt = 0;
let refreshInFlight = null;

function readStoredAuth() {
  try {
    const stored = JSON.parse(localStorage.getItem(AUTH_STORAGE_KEY));
    return stored && stored.refreshToken && stored.account ? stored : null;
  } catch (e) {
    return null;
  }
}

function writeStoredAuth() {
  try {
    if (auth) {
      localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(auth));
    } else {
      localStorage.removeItem(AUTH_STORAGE_KEY);
    }
  } catch (e) {
    // Storage blocked: sign-in still works for this page view.
  }
}

function setAuth(session) {
  auth = { refreshToken: session.refresh_token, account: session.account };
  accessToken = session.access_token;
  accessTokenExpiresAt = Date.now() + session.expires_in * 1000;
  writeStoredAuth();
  renderAccount();
}

function clearAuth() {
  auth = null;
  accessToken = null;
  accessTokenExpiresAt = 0;
  writeStoredAuth();
  renderAccount();
}

async function authPost(path, body) {
  const response = await fetch(API_BASE + path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(data.error || `sign-in failed (${response.status})`);
    error.status = response.status;
    throw error;
  }
  return data;
}

// Exchanges the refresh token for a new access token. Resolves to the token, or
// null when signed out or offline. Only a *rejected* refresh token signs the
// visitor out; a network failure keeps them signed in to retry later.
function refreshAccessToken() {
  if (!auth) return Promise.resolve(null);
  if (refreshInFlight) return refreshInFlight;
  refreshInFlight = (async () => {
    try {
      let used = auth.refreshToken;
      for (let attempt = 0; attempt < 2; attempt++) {
        try {
          setAuth(await authPost("/api/auth/refresh", { refresh_token: used }));
          return accessToken;
        } catch (e) {
          if (e.status !== 401 && e.status !== 403) return null; // offline: stay signed in
          // Refresh tokens rotate: another tab may have just used this one. If so,
          // retry once with the newer token it stored instead of signing out.
          const stored = readStoredAuth();
          if (attempt === 0 && stored && stored.refreshToken !== used) {
            auth = stored;
            accessToken = null;
            used = stored.refreshToken;
            continue;
          }
          clearAuth();
          return null;
        }
      }
      return null;
    } finally {
      refreshInFlight = null;
    }
  })();
  return refreshInFlight;
}

async function currentAccessToken() {
  if (!auth) return null;
  if (accessToken && Date.now() < accessTokenExpiresAt - ACCESS_TOKEN_MARGIN_MS) {
    return accessToken;
  }
  return refreshAccessToken();
}

let googleScript = null;
function loadGoogleScript() {
  if (!googleScript) {
    googleScript = new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = "https://accounts.google.com/gsi/client";
      script.async = true;
      script.onload = resolve;
      script.onerror = () => {
        googleScript = null;
        reject(new Error("Could not load Google sign-in."));
      };
      document.head.appendChild(script);
    });
  }
  return googleScript;
}

let googleButtonReady = false;
async function prepareGoogleButton() {
  const errorEl = $("signin-error");
  errorEl.hidden = true;
  if (googleButtonReady) return;
  try {
    await loadGoogleScript();
  } catch (e) {
    errorEl.textContent = e.message;
    errorEl.hidden = false;
    return;
  }
  google.accounts.id.initialize({
    client_id: GOOGLE_WEB_CLIENT_ID,
    callback: async ({ credential }) => {
      errorEl.hidden = true;
      try {
        setAuth(await authPost("/api/auth/google", { id_token: credential }));
        setAccountPanelOpen(false);
      } catch (e) {
        errorEl.textContent = e.message;
        errorEl.hidden = false;
      }
    },
  });
  google.accounts.id.renderButton($("google-button"), {
    type: "standard",
    theme: "outline",
    size: "large",
    text: "signin_with",
  });
  googleButtonReady = true;
}

function setAccountPanelOpen(open) {
  $("account-panel").hidden = !open;
  $("account-btn").setAttribute("aria-expanded", String(open));
  if (open && !auth) prepareGoogleButton();
}

function renderAccount() {
  if (!GOOGLE_WEB_CLIENT_ID) return;
  $("account").hidden = false;
  $("signed-out").hidden = !!auth;
  $("signed-in").hidden = !auth;
  // First name only: the top bar is tight on a phone. The panel shows the full name.
  $("account-btn").textContent = auth
    ? (auth.account.name || "").split(" ")[0] || auth.account.email || "Account"
    : "Sign in";
  if (auth) {
    $("account-name").textContent = auth.account.name || auth.account.email;
    $("account-email").textContent = auth.account.name ? auth.account.email : "";
  }
}

$("account-btn").addEventListener("click", () => setAccountPanelOpen($("account-panel").hidden));
document.addEventListener("click", (event) => {
  if (!$("account").contains(event.target)) setAccountPanelOpen(false);
});
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") setAccountPanelOpen(false);
});

$("sign-out").addEventListener("click", async () => {
  const refreshToken = auth && auth.refreshToken;
  clearAuth();
  setAccountPanelOpen(false);
  if (window.google && google.accounts) google.accounts.id.disableAutoSelect();
  if (refreshToken) {
    try {
      await authPost("/api/auth/logout", { refresh_token: refreshToken });
    } catch (e) {
      // Already signed out here; the server-side token just expires on its own.
    }
  }
});

$("delete-account").addEventListener("click", async () => {
  if (!window.confirm("Delete your Parking Blues account and its stored data? This cannot be undone.")) return;
  try {
    await api("/api/me", { method: "DELETE" });
    clearAuth();
    setAccountPanelOpen(false);
  } catch (e) {
    showStatus(`Couldn't delete the account: ${e.message}`);
  }
});

renderAccount();
if (auth) refreshAccessToken();

// --- Parking near the destination -----------------------------------------------
// The page answers the moment it opens: parking around where the visitor is (or
// central Zurich), both zones, no questions asked. Zone, stay length, address
// search and the pin only exist to correct that answer. All ranking and rules
// come from the server (GET /api/nearby); this code only draws them.

const ZONES = ["both", "blue", "white"];
const state = {
  destination: { ...ZURICH_CENTER },
  zone: "both",
  stay: null, // minutes, or null for "any"
  areas: [],
  selected: null, // rank of the expanded area
  // Where the page opened (the visitor's location or central Zurich): what
  // Back returns to once the history holds no destination of its own.
  home: { ...ZURICH_CENTER },
};
let requestSeq = 0;
state.destinationChosen = false; // true once the visitor has picked a destination

function inZurich({ lat, lng }) {
  return lat >= ZURICH_BOUNDS.south && lat <= ZURICH_BOUNDS.north &&
    lng >= ZURICH_BOUNDS.west && lng <= ZURICH_BOUNDS.east;
}

// Why the list is empty: a filter the visitor chose, or the pin being outside the data.
function emptyMessage() {
  if (state.zone !== "both" || state.stay) {
    const zone = state.zone === "both" ? "" : `${state.zone} zone `;
    const stay = state.stay ? ` that allow a ${formatMinutes(state.stay)} stay` : "";
    return `No ${zone}parking within 1 km of the pin${stay}. Try all zones or a shorter stay` +
      (state.zone === "blue" && state.stay > 90 ? " (blue zones allow about 1 to 1.5 hours)." : ".");
  }
  return "No public parking found within 1 km of the pin. The data covers the city of Zurich: " +
    "move the pin or search an address.";
}

// Shows (or, with no message, clears) a short notice under the map.
function showStatus(message) {
  const el = $("status");
  el.textContent = message || "";
  el.hidden = !message;
}

// --- Rules, as words -------------------------------------------------------------

function hhmm(iso) {
  return iso.substring(11, 16);
}

// "11:30", or "tomorrow 09:00" when the time falls on a later date than `nowIso`.
function clockLabel(iso, nowIso) {
  return iso.substring(0, 10) === nowIso.substring(0, 10) ? hhmm(iso) : `tomorrow ${hhmm(iso)}`;
}

function formatMinutes(minutes) {
  if (minutes < 60) return `${minutes} min`;
  const hours = minutes / 60;
  return Number.isInteger(hours) ? `${hours} h` : `${hours.toFixed(1)} h`;
}

function zoneName(area) {
  return area.zone_type === "blue" ? "Blue zone" : "White zone";
}

function spacesText(area) {
  return `~${area.capacity} space${area.capacity === 1 ? "" : "s"}`;
}

// What the visitor needs to know to park here, for this area right now.
function rulesText(area, nowIso) {
  if (area.zone_type === "blue") {
    if (area.disc_mark) {
      return `Set your disc to ${hhmm(area.disc_mark)} · park until ${clockLabel(area.legal_until, nowIso)}`;
    }
    return area.legal_until
      ? `No disc needed now · free until ${clockLabel(area.legal_until, nowIso)}`
      : "No time limit right now";
  }
  const parts = [area.max_duration_minutes ? `${formatMinutes(area.max_duration_minutes)} max` : "no fixed limit"];
  const rate = area.estimated_fee_chf_per_hour;
  if (rate) {
    parts.push(`~CHF ${rate.toFixed(2)}/h`);
    if (state.stay) parts.push(`~CHF ${(rate * state.stay / 60).toFixed(2)} for ${formatMinutes(state.stay)}`);
  }
  return parts.join(" · ");
}

// Opens the visitor's navigation app on the area: Apple Maps on iOS, otherwise
// Google Maps (which hands over to the app on Android).
function navigateUrl(area) {
  const ios = /iPad|iPhone|iPod/.test(navigator.userAgent) ||
    (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  const target = `${area.lat},${area.lon}`;
  return ios
    ? `https://maps.apple.com/?daddr=${target}&dirflg=d`
    : `https://www.google.com/maps/dir/?api=1&destination=${target}&travelmode=driving`;
}

// --- Drawing -----------------------------------------------------------------------

function badgeIcon(area, selected) {
  return L.divIcon({
    className: "area-badge-wrap",
    html: `<span class="area-badge ${area.zone_type}${selected ? " selected" : ""}">${area.rank}</span>`,
    iconSize: [28, 28],
  });
}

function drawMap(fit) {
  areaLayer.clearLayers();
  // Weakest first so the best areas end up on top.
  for (const area of [...state.areas].reverse()) {
    const selected = area.rank === state.selected;
    for (const [lat, lon] of area.spots) {
      L.circleMarker([lat, lon], {
        className: area.zone_type === "blue" ? "spot-blue" : "spot-white",
        radius: selected ? 7 : 5,
        fillOpacity: selected ? 0.95 : 0.55,
        weight: selected ? 3 : 1,
        interactive: false,
      }).addTo(areaLayer);
    }
    L.marker([area.lat, area.lon], { icon: badgeIcon(area, selected), keyboard: false, zIndexOffset: selected ? 1000 : 0 })
      .on("click", () => (mapIsFullscreen() ? showAreaPopup(area) : selectArea(area.rank, { scroll: true })))
      .addTo(areaLayer);
  }
  if (fit && state.areas.length) {
    const points = [[state.destination.lat, state.destination.lng]];
    for (const area of state.areas.slice(0, 5)) points.push([area.lat, area.lon]);
    map.fitBounds(L.latLngBounds(points), { padding: [36, 36], maxZoom: 17 });
  }
}

function renderAreas(data) {
  const list = $("areas");
  list.innerHTML = "";
  for (const area of state.areas) {
    const li = document.createElement("li");
    li.className = `area ${area.zone_type}`;
    li.dataset.rank = String(area.rank);
    const expanded = area.rank === state.selected;
    const button = document.createElement("button");
    button.type = "button";
    button.className = "area-row";
    button.setAttribute("aria-expanded", String(expanded));
    button.innerHTML = `
      <span class="area-badge ${area.zone_type}">${area.rank}</span>
      <span class="area-text">
        <span class="area-title">${zoneName(area)} · ${Math.round(area.distance_m)} m from the pin</span>
        <span class="area-sub">${spacesText(area)} · ${rulesText(area, data.generated_at)}</span>
      </span>`;
    button.addEventListener("click", () => selectArea(expanded ? null : area.rank, { scroll: false }));
    li.appendChild(button);
    if (expanded) {
      const details = document.createElement("div");
      details.className = "area-details";
      const nav = document.createElement("a");
      nav.className = "button-link primary";
      nav.href = navigateUrl(area);
      nav.target = "_blank";
      nav.rel = "noopener";
      nav.textContent = "Navigate";
      details.appendChild(nav);
      li.appendChild(details);
    }
    list.appendChild(li);
  }
}

// The one thing worth saying about blue zones, once, at the top: what to do
// with the parking disc right now.
function renderDisc(data) {
  const card = $("disc");
  const blue = state.areas.find((area) => area.zone_type === "blue");
  if (!blue) {
    card.hidden = true;
    return;
  }
  const now = data.generated_at;
  card.innerHTML = blue.disc_mark
    ? `<strong>Parking disc:</strong> set it to <strong>${hhmm(blue.disc_mark)}</strong>. You may park until ${clockLabel(blue.legal_until, now)}.`
    : `<strong>Parking disc:</strong> not needed right now. ` +
      (blue.legal_until ? `Move your car by ${clockLabel(blue.legal_until, now)}.` : "There is no time limit at the moment.");
  card.hidden = false;
}

function render(data, { fit }) {
  state.areas = data.areas;
  if (!state.areas.some((area) => area.rank === state.selected)) state.selected = null;

  if (state.areas.length) {
    const radius = data.radius_m >= 1000 ? `${data.radius_m / 1000} km` : `${Math.round(data.radius_m)} m`;
    showStatus("");
    $("summary").textContent = `${state.areas.length} area${state.areas.length === 1 ? "" : "s"} within ${radius} of the pin`;
  } else {
    $("summary").textContent = "";
    showStatus(emptyMessage());
  }
  renderDisc(data);
  renderAreas(data);
  drawMap(fit);
}

function selectArea(rank, { scroll }) {
  state.selected = rank;
  const area = state.areas.find((a) => a.rank === rank);
  renderAreas({ generated_at: lastGeneratedAt });
  drawMap(false);
  if (area && scroll) {
    document.querySelector(`.area[data-rank="${rank}"]`)?.scrollIntoView({ block: "nearest", behavior: "smooth" });
  }
  if (area) map.panTo([area.lat, area.lon]);
}

let lastGeneratedAt = new Date().toISOString();

async function loadNearby({ fit }) {
  const seq = ++requestSeq;
  const params = new URLSearchParams({
    lat: state.destination.lat.toFixed(6),
    lon: state.destination.lng.toFixed(6),
    zone: state.zone,
  });
  if (state.stay) params.set("stay", String(state.stay));
  $("areas").setAttribute("aria-busy", "true");
  let data;
  try {
    data = await api(`/api/nearby?${params}`);
  } catch (err) {
    if (seq !== requestSeq) return;
    $("areas").removeAttribute("aria-busy");
    showStatus(`Couldn't load parking: ${err.message}`);
    return;
  }
  if (seq !== requestSeq) return; // the visitor has moved on
  $("areas").removeAttribute("aria-busy");
  lastGeneratedAt = data.generated_at;
  render(data, { fit });
}

// --- Changing the destination --------------------------------------------------------

// The search in the address, e.g. #lat=47.37921&lng=8.53131&zone=blue&stay=60:
// every destination the visitor picks is a history entry, so Back returns to the
// previous one and a link carries the search. It is the URL *fragment*, which
// browsers never send to a server, so it stays out of hosting and API logs. The
// location the page opens with is deliberately not written into the address.
function searchHash() {
  const params = new URLSearchParams({
    lat: state.destination.lat.toFixed(5),
    lng: state.destination.lng.toFixed(5),
  });
  if (state.zone !== "both") params.set("zone", state.zone);
  if (state.stay) params.set("stay", String(state.stay));
  return `#${params}`;
}

function parseSearchHash() {
  const params = new URLSearchParams(window.location.hash.slice(1));
  const lat = parseFloat(params.get("lat"));
  const lng = parseFloat(params.get("lng"));
  if (!(Math.abs(lat) <= 90) || !(Math.abs(lng) <= 180)) return null;
  const zone = params.get("zone") || "both";
  const stayRaw = params.get("stay");
  const stay = stayRaw ? parseInt(stayRaw, 10) : null;
  if (!ZONES.includes(zone) || (stayRaw && !(stay > 0))) return null;
  return { lat, lng, zone, stay };
}

function syncControls() {
  for (const button of document.querySelectorAll("#zone-filter button")) {
    button.setAttribute("aria-pressed", String(button.dataset.zone === state.zone));
  }
  $("stay").value = state.stay ? String(state.stay) : "";
}

// Moves the destination and refreshes. `push` adds a history entry.
function setDestination({ lat, lng }, { label = "", push = true, fit = true } = {}) {
  state.destination = { lat, lng };
  state.selected = null;
  destinationMarker.setLatLng([lat, lng]);
  $("search").value = label;
  if (push) {
    state.destinationChosen = true;
    history.pushState({ zone: state.zone, stay: state.stay }, "", searchHash());
  }
  if (fit) map.setView([lat, lng], Math.max(map.getZoom(), 16));
  loadNearby({ fit: true });
}

destinationMarker.on("dragend", () => {
  const { lat, lng } = destinationMarker.getLatLng();
  setDestination({ lat, lng }, { fit: false });
});
map.on("click", (event) => {
  setDestination({ lat: event.latlng.lat, lng: event.latlng.lng }, { fit: false });
});

window.addEventListener("popstate", (event) => {
  // Back out of the overlay map: leave full screen, keep the destination.
  if (pseudoFullscreen) {
    leavePseudoFullscreen();
    return;
  }
  const search = parseSearchHash();
  // An entry without a destination in its address is where the page opened; the
  // filters the visitor had set there are kept in the entry's state.
  const saved = event.state || {};
  const target = search || { ...state.home, zone: saved.zone || "both", stay: saved.stay || null };
  state.zone = target.zone;
  state.stay = target.stay;
  syncControls();
  setDestination({ lat: target.lat, lng: target.lng }, { push: false });
});

// --- Zone and stay ----------------------------------------------------------------------

for (const button of document.querySelectorAll("#zone-filter button")) {
  button.addEventListener("click", () => {
    if (state.zone === button.dataset.zone) return;
    state.zone = button.dataset.zone;
    syncControls();
    history.replaceState({ zone: state.zone, stay: state.stay }, "", state.destinationChosen ? searchHash() : window.location.href);
    loadNearby({ fit: false });
  });
}

$("stay").addEventListener("change", () => {
  state.stay = $("stay").value ? parseInt($("stay").value, 10) : null;
  history.replaceState({ zone: state.zone, stay: state.stay }, "", state.destinationChosen ? searchHash() : window.location.href);
  loadNearby({ fit: false });
});

// --- "Use my location" ----------------------------------------------------------------

function useMyLocation({ silent }) {
  if (!navigator.geolocation) {
    if (!silent) showStatus("Your browser can't share your location. Search an address or drag the pin.");
    return;
  }
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      const here = { lat: pos.coords.latitude, lng: pos.coords.longitude };
      if (!inZurich(here)) {
        showStatus("You're outside Zurich, where the parking data ends. Showing central Zurich instead.");
        return;
      }
      state.home = here;
      state.destinationChosen = !silent;
      showStatus("");
      setDestination(here, { label: "", push: !silent, fit: true });
    },
    () => {
      if (!silent) showStatus("Couldn't get your location. Allow location access, or search an address.");
    },
    { enableHighAccuracy: false, timeout: 10000, maximumAge: 60000 }
  );
}

$("use-geolocation").addEventListener("click", () => useMyLocation({ silent: false }));

// --- Address search (swisstopo) --------------------------------------------------------
// Swiss addresses and place names, free and without a key. The query goes straight
// from the browser to swisstopo (see the privacy policy); a box around Zurich keeps
// "Bahnhofstrasse" from returning every town's.

const SEARCH_URL = "https://api3.geo.admin.ch/rest/services/api/SearchServer";
const SEARCH_BBOX = "2672000,1239000,2692000,1256000"; // LV95, around the city
let suggestions = [];
let activeSuggestion = -1;
let suggestTimer = null;
let suggestSeq = 0;

function stripTags(html) {
  return html.replace(/<[^>]*>/g, "");
}

function hideSuggestions() {
  suggestions = [];
  activeSuggestion = -1;
  $("suggestions").hidden = true;
  $("suggestions").innerHTML = "";
  $("search").setAttribute("aria-expanded", "false");
}

function showSuggestions() {
  const list = $("suggestions");
  list.innerHTML = "";
  suggestions.forEach((suggestion, index) => {
    const li = document.createElement("li");
    li.setAttribute("role", "option");
    li.setAttribute("aria-selected", String(index === activeSuggestion));
    li.textContent = suggestion.label;
    // mousedown, not click: the input's blur would hide the list before a click lands.
    li.addEventListener("mousedown", (event) => {
      event.preventDefault();
      chooseSuggestion(index);
    });
    list.appendChild(li);
  });
  list.hidden = suggestions.length === 0;
  $("search").setAttribute("aria-expanded", String(suggestions.length > 0));
}

function chooseSuggestion(index) {
  const suggestion = suggestions[index];
  if (!suggestion) return;
  hideSuggestions();
  state.destinationChosen = true;
  setDestination({ lat: suggestion.lat, lng: suggestion.lng }, { label: suggestion.label });
  $("search").blur();
}

async function fetchSuggestions(query) {
  const seq = ++suggestSeq;
  const params = new URLSearchParams({
    searchText: query,
    type: "locations",
    origins: "address,gazetteer,zipcode",
    sr: "2056",
    bbox: SEARCH_BBOX,
    limit: "6",
  });
  try {
    const response = await fetch(`${SEARCH_URL}?${params}`);
    if (!response.ok) throw new Error(String(response.status));
    const data = await response.json();
    if (seq !== suggestSeq) return;
    suggestions = (data.results || [])
      .filter((r) => r.attrs && Number.isFinite(r.attrs.lat) && Number.isFinite(r.attrs.lon))
      .map((r) => ({ label: stripTags(r.attrs.label), lat: r.attrs.lat, lng: r.attrs.lon }));
    activeSuggestion = suggestions.length ? 0 : -1;
    showSuggestions();
    if (!suggestions.length) showStatus(`Nothing found for "${query}". Try a street and number, or a place name.`);
  } catch (err) {
    if (seq !== suggestSeq) return;
    hideSuggestions();
    showStatus("Address search isn't available right now. Drag the pin or tap the map instead.");
  }
}

$("search").addEventListener("input", () => {
  clearTimeout(suggestTimer);
  const query = $("search").value.trim();
  if (query.length < 3) {
    suggestSeq++;
    hideSuggestions();
    return;
  }
  suggestTimer = setTimeout(() => fetchSuggestions(query), 250);
});

$("search").addEventListener("keydown", (event) => {
  if (event.key === "ArrowDown" && suggestions.length) {
    event.preventDefault();
    activeSuggestion = (activeSuggestion + 1) % suggestions.length;
    showSuggestions();
  } else if (event.key === "ArrowUp" && suggestions.length) {
    event.preventDefault();
    activeSuggestion = (activeSuggestion - 1 + suggestions.length) % suggestions.length;
    showSuggestions();
  } else if (event.key === "Enter") {
    event.preventDefault();
    if (suggestions.length) chooseSuggestion(Math.max(activeSuggestion, 0));
  } else if (event.key === "Escape") {
    hideSuggestions();
  }
});

$("search").addEventListener("blur", () => setTimeout(hideSuggestions, 150));

// --- Full-screen map -------------------------------------------------------------
// The real Fullscreen API where the browser allows it (no margins, no browser
// toolbar). iPhone Safari only does that for video, so there (or if the request
// is refused) the map instead fills the whole viewport as an overlay; Back and
// Escape leave it, as the on-map button does.

const mapEl = $("map");
let pseudoFullscreen = false;
let fullscreenLink = null;

const ICON_ENTER = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" /></svg>';
const ICON_EXIT = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5" /></svg>';

function nativeFullscreenElement() {
  return document.fullscreenElement || document.webkitFullscreenElement || null;
}

function mapIsFullscreen() {
  return pseudoFullscreen || nativeFullscreenElement() === mapEl;
}

function refreshFullscreenUi() {
  const on = mapIsFullscreen();
  if (fullscreenLink) {
    fullscreenLink.innerHTML = on ? ICON_EXIT : ICON_ENTER;
    const label = on ? "Exit full screen" : "Full screen map";
    fullscreenLink.title = label;
    fullscreenLink.setAttribute("aria-label", label);
  }
  document.body.classList.toggle("map-fullscreen-open", pseudoFullscreen);
  if (!on) map.closePopup();
  // The container changed size: let Leaflet re-measure once the layout has settled.
  setTimeout(() => map.invalidateSize(), 60);
}

async function enterFullscreen() {
  const request = mapEl.requestFullscreen || mapEl.webkitRequestFullscreen;
  if (request) {
    try {
      await request.call(mapEl);
      return; // fullscreenchange updates the UI
    } catch (e) {
      // Refused (no user gesture, policy): use the overlay instead.
    }
  }
  pseudoFullscreen = true;
  mapEl.classList.add("map-fullscreen");
  // A history entry, so the phone's Back button leaves full screen rather than the page.
  history.pushState({ ...(history.state || {}), fullscreen: true }, "", window.location.href);
  refreshFullscreenUi();
}

function leavePseudoFullscreen() {
  pseudoFullscreen = false;
  mapEl.classList.remove("map-fullscreen");
  refreshFullscreenUi();
}

function exitFullscreen() {
  if (nativeFullscreenElement()) {
    (document.exitFullscreen || document.webkitExitFullscreen).call(document);
  } else if (pseudoFullscreen) {
    if (history.state && history.state.fullscreen) history.back(); // popstate leaves it
    else leavePseudoFullscreen();
  }
}

const FullscreenControl = L.Control.extend({
  options: { position: "topright" },
  onAdd() {
    const bar = L.DomUtil.create("div", "leaflet-bar leaflet-control fullscreen-control");
    const link = L.DomUtil.create("a", "", bar);
    link.href = "#";
    link.setAttribute("role", "button");
    fullscreenLink = link;
    L.DomEvent.disableClickPropagation(bar);
    L.DomEvent.on(link, "click", (event) => {
      L.DomEvent.preventDefault(event);
      if (mapIsFullscreen()) exitFullscreen();
      else enterFullscreen();
    });
    refreshFullscreenUi();
    return bar;
  },
});
new FullscreenControl().addTo(map);

document.addEventListener("fullscreenchange", refreshFullscreenUi);
document.addEventListener("webkitfullscreenchange", refreshFullscreenUi);
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && pseudoFullscreen) exitFullscreen();
});

// With the list out of sight, tapping an area's badge shows its details in place.
function showAreaPopup(area) {
  L.popup({ offset: [0, -8] })
    .setLatLng([area.lat, area.lon])
    .setContent(
      `<strong>${zoneName(area)} · ${Math.round(area.distance_m)} m from the pin</strong><br>` +
      `${spacesText(area)}<br>${rulesText(area, lastGeneratedAt)}<br>` +
      `<a href="${navigateUrl(area)}" target="_blank" rel="noopener">Navigate</a>`
    )
    .openOn(map);
}

// --- Opening the page: answer first ------------------------------------------------------

async function start() {
  const fromLink = parseSearchHash();
  if (fromLink) {
    // A link (or a reload) carries a destination: use it, and ask for nothing.
    state.zone = fromLink.zone;
    state.stay = fromLink.stay;
    state.destinationChosen = true;
    syncControls();
    state.destination = { lat: fromLink.lat, lng: fromLink.lng };
    destinationMarker.setLatLng([fromLink.lat, fromLink.lng]);
    map.setView([fromLink.lat, fromLink.lng], 16);
    loadNearby({ fit: true });
    return;
  }

  // Otherwise answer straight away for central Zurich, and refine to where the
  // visitor actually is as soon as the browser tells us (skipped if they have
  // already said no, so nobody is nagged).
  syncControls();
  loadNearby({ fit: true });
  let permission = "prompt";
  try {
    permission = (await navigator.permissions.query({ name: "geolocation" })).state;
  } catch (e) {
    // The Permissions API is not available everywhere; asking is still fine.
  }
  if (permission !== "denied") useMyLocation({ silent: true });
}

start();


// Theme: "light" / "dark" force a choice regardless of OS setting; "system"
// (the default) defers to prefers-color-scheme, handled in style.css.
const THEME_STORAGE_KEY = "parking-blues-theme";
const themeButtons = document.querySelectorAll("#theme-toggle button");

function applyTheme(theme) {
  if (theme === "system") {
    document.documentElement.removeAttribute("data-theme");
  } else {
    document.documentElement.setAttribute("data-theme", theme);
  }
  for (const btn of themeButtons) {
    btn.setAttribute("aria-pressed", String(btn.dataset.theme === theme));
  }
}

for (const btn of themeButtons) {
  btn.addEventListener("click", () => {
    localStorage.setItem(THEME_STORAGE_KEY, btn.dataset.theme);
    applyTheme(btn.dataset.theme);
  });
}

applyTheme(localStorage.getItem(THEME_STORAGE_KEY) || "dark");
