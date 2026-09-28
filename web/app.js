let sessionId = null;

const DEFAULT_ORIGIN = { lat: 47.379198, lon: 8.531307 };

const $ = (id) => document.getElementById(id);

const map = L.map("map").setView([DEFAULT_ORIGIN.lat, DEFAULT_ORIGIN.lon], 16);
// CartoDB's "Positron" basemap: a light, minimal OSM-derived style that
// keeps streets/labels/buildings/rail but drops the POI icon clutter
// (restaurants, shops, etc.) of the default OSM tiles. CARTO started
// requiring a (free) API key for this on 2026-08-28 -- anonymous requests
// now come back watermarked "API KEY REQUIRED" instead of erroring, so a
// missing/invalid key fails visually rather than loudly.
const CARTO_API_KEY = "cb1_40s3_1_3dff2462a8c5ca48a8208963";
L.tileLayer(
  `https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png?key=${CARTO_API_KEY}`,
  {
    attribution:
      "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors &copy; <a href=\"https://carto.com/attributions\">CARTO</a>",
    maxZoom: 19,
  }
).addTo(map);

const originMarker = L.marker([DEFAULT_ORIGIN.lat, DEFAULT_ORIGIN.lon], {
  draggable: true,
}).addTo(map).bindTooltip("Destination", { permanent: true, direction: "top" });

const youIcon = L.divIcon({
  className: "you-marker",
  html: '<div class="you-dot"></div>',
  iconSize: [16, 16],
});
let youMarker = null;
let radiusCircle = null;
let candidateLayer = L.layerGroup().addTo(map);

function updateOriginReadout() {
  const { lat, lng } = originMarker.getLatLng();
  $("origin-readout").textContent = `Destination: ${lat.toFixed(5)}, ${lng.toFixed(5)}`;
}

originMarker.on("dragend", updateOriginReadout);

function log(message) {
  const li = document.createElement("li");
  const time = new Date().toLocaleTimeString();
  li.textContent = `[${time}] ${message}`;
  $("log").prepend(li);
}

function selectedZone() {
  return document.querySelector('input[name="zone"]:checked').value;
}

function selectedDuration() {
  const value = $("duration").value;
  return value ? parseInt(value, 10) : null;
}

function formatDuration(segment) {
  if (segment.zone_type === "blue") {
    if (!segment.legal_until) {
      // Sundays (and, not modeled, public holidays) are unrestricted.
      return "no time limit right now";
    }
    const until = new Date(segment.legal_until);
    const label = until.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
    return `park until ~${label} (set your disc)`;
  }
  const cap = segment.max_duration_minutes
    ? `${segment.max_duration_minutes} min max`
    : "no fixed limit";
  const rate = segment.estimated_fee_chf_per_hour;
  return rate ? `${cap} &middot; ~CHF ${rate.toFixed(2)}/h (rough estimate)` : cap;
}

// Shared by the "current target" card and the map popups (candidateMarker/
// rejectedMarker below) so a spot shows the same information everywhere
// it's shown, not a trimmed-down version in one place and not the other.
function segmentDetailsHtml(segment) {
  const zoneClass = segment.zone_type === "blue" ? "zone-blue" : "zone-white";
  const duration = formatDuration(segment);
  // distance_from_you_m (live, from the driver) is what matters moment to
  // moment while driving; distance_m (fixed, from the destination) is kept
  // as context so a target's "am I actually headed the right way" story
  // doesn't disappear -- see the "why far away" note this replaced.
  return `
    <div class="${zoneClass}">${segment.zone_type.toUpperCase()} ZONE</div>
    <div>${segment.address_label}</div>
    <div>${segment.distance_from_you_m} m from you (${segment.distance_m} m from destination) &middot; capacity ~${segment.estimated_capacity} &middot; ${duration}</div>
  `;
}

function renderSegment(container, segment) {
  if (!segment) {
    container.textContent = "No candidates.";
    return;
  }
  container.innerHTML = segmentDetailsHtml(segment);
}

function candidateMarker(segment, highlighted) {
  const color = segment.zone_type === "blue" ? "#1560bd" : "#555";
  return L.circleMarker([segment.lat, segment.lon], {
    radius: highlighted ? 10 : 6,
    color,
    fillColor: color,
    fillOpacity: highlighted ? 0.9 : 0.4,
    weight: highlighted ? 3 : 1,
  }).bindPopup(`<div class="popup-card">${segmentDetailsHtml(segment)}</div>`);
}

function rejectedMarker(segment) {
  return L.circleMarker([segment.lat, segment.lon], {
    radius: 6,
    color: "#999",
    fillColor: "#bbb",
    fillOpacity: 0.5,
    weight: 1,
  }).bindPopup(
    `<div class="popup-card">${segmentDetailsHtml(segment)}<div class="popup-note">No space -- already checked</div></div>`
  );
}

function renderMap(data) {
  candidateLayer.clearLayers();
  // Draw already-checked spots first (and dimmed) so they sit visually
  // behind the still-live candidates instead of competing with them.
  for (const segment of data.rejected) {
    rejectedMarker(segment).addTo(candidateLayer);
  }
  if (data.current) {
    candidateMarker(data.current, true).addTo(candidateLayer);
  }
  for (const segment of data.upcoming) {
    candidateMarker(segment, false).addTo(candidateLayer);
  }

  if (radiusCircle) {
    radiusCircle.setLatLng([data.origin.lat, data.origin.lon]);
    radiusCircle.setRadius(data.radius_m);
  } else {
    radiusCircle = L.circle([data.origin.lat, data.origin.lon], {
      radius: data.radius_m,
      color: "#888",
      fill: false,
      dashArray: "4 4",
    }).addTo(map);
  }
}

function render(data) {
  // "tracking" fires on every drag tick -- logging it would drown out the
  // events that actually matter (rejection, retargeting, expansion).
  if (data.event && data.event !== "tracking") {
    const target = data.current ? data.current.address_label : "(none)";
    const radiusNote = data.event === "expanded" ? ` (radius now ${data.radius_m} m)` : "";
    log(`${data.event}${radiusNote} -> current target: ${target}`);
  }

  renderSegment($("current"), data.current);
  renderMap(data);

  $("duration-readout").textContent = data.preferred_duration_minutes
    ? `Only showing spots you can stay at for at least ${data.preferred_duration_minutes} min, right now.`
    : "";

  const exhausted = data.state === "exhausted";
  $("exhausted").hidden = !exhausted;

  const list = $("upcoming");
  list.innerHTML = "";
  for (const segment of data.upcoming) {
    const li = document.createElement("li");
    li.textContent = `${segment.address_label} (${segment.zone_type}, ${segment.distance_from_you_m} m from you)`;
    list.appendChild(li);
  }
}

async function api(path, options) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  const data = await response.json();
  if (!response.ok) {
    throw new Error(data.error || `request to ${path} failed`);
  }
  return data;
}

$("use-geolocation").addEventListener("click", () => {
  if (!navigator.geolocation) {
    log("Geolocation is not available in this browser.");
    return;
  }
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      const { latitude, longitude } = pos.coords;
      originMarker.setLatLng([latitude, longitude]);
      map.setView([latitude, longitude], 16);
      updateOriginReadout();
    },
    (err) => log(`Geolocation failed: ${err.message}`)
  );
});

$("start").addEventListener("click", async () => {
  const { lat, lng } = originMarker.getLatLng();
  const zone = selectedZone();
  const duration_minutes = selectedDuration();
  const data = await api("/api/session", {
    method: "POST",
    body: JSON.stringify({ lat, lon: lng, zone, duration_minutes }),
  });
  sessionId = data.session_id;
  $("setup").hidden = true;
  $("session").hidden = false;

  originMarker.dragging.disable();
  youMarker = L.marker([lat, lng], { draggable: true, icon: youIcon })
    .addTo(map)
    .bindTooltip("You (drag to simulate driving)", { direction: "top" });

  // Post position updates continuously while dragging (not just on drop) so
  // the approach/depart auto-rejection and live retargeting behave like an
  // actual drive-by instead of needing repeated discrete drops. Throttled
  // by both a time interval and an in-flight guard so drag ticks don't pile
  // up requests; dragend always sends the final position.
  const POSITION_UPDATE_INTERVAL_MS = 200;
  let positionRequestInFlight = false;
  let lastPositionSentAt = 0;

  async function sendPosition(pos) {
    if (positionRequestInFlight) return;
    positionRequestInFlight = true;
    lastPositionSentAt = Date.now();
    try {
      const body = await api(`/api/session/${sessionId}/position`, {
        method: "POST",
        body: JSON.stringify({ lat: pos.lat, lon: pos.lng }),
      });
      render(body);
    } finally {
      positionRequestInFlight = false;
    }
  }

  youMarker.on("drag", () => {
    if (Date.now() - lastPositionSentAt < POSITION_UPDATE_INTERVAL_MS) return;
    sendPosition(youMarker.getLatLng());
  });
  youMarker.on("dragend", () => sendPosition(youMarker.getLatLng()));

  log(`Session started (radius ${data.radius_m} m)`);
  render(data);
});

$("expand").addEventListener("click", async () => {
  const data = await api(`/api/session/${sessionId}/expand`, { method: "POST" });
  render(data);
});

$("reset").addEventListener("click", () => {
  sessionId = null;
  if (youMarker) {
    map.removeLayer(youMarker);
    youMarker = null;
  }
  candidateLayer.clearLayers();
  if (radiusCircle) {
    map.removeLayer(radiusCircle);
    radiusCircle = null;
  }
  originMarker.dragging.enable();
  $("log").innerHTML = "";
  $("duration-readout").textContent = "";
  $("exhausted").hidden = true;
  $("session").hidden = true;
  $("setup").hidden = false;
});

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

applyTheme(localStorage.getItem(THEME_STORAGE_KEY) || "system");
