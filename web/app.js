let sessionId = null;

const DEFAULT_ORIGIN = { lat: 47.379198, lon: 8.531307 };

const $ = (id) => document.getElementById(id);

const map = L.map("map").setView([DEFAULT_ORIGIN.lat, DEFAULT_ORIGIN.lon], 16);
L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
  attribution: "&copy; OpenStreetMap contributors",
  maxZoom: 19,
}).addTo(map);

const originMarker = L.marker([DEFAULT_ORIGIN.lat, DEFAULT_ORIGIN.lon], {
  draggable: true,
}).addTo(map).bindTooltip("Start", { permanent: true, direction: "top" });

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
  $("origin-readout").textContent = `Start: ${lat.toFixed(5)}, ${lng.toFixed(5)}`;
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

function renderSegment(container, segment) {
  if (!segment) {
    container.textContent = "No candidates.";
    return;
  }
  const zoneClass = segment.zone_type === "blue" ? "zone-blue" : "zone-white";
  const duration = segment.max_duration_minutes
    ? `${segment.max_duration_minutes} min max`
    : "no fixed limit";
  container.innerHTML = `
    <div class="${zoneClass}">${segment.zone_type.toUpperCase()} ZONE</div>
    <div>${segment.address_label}</div>
    <div>${segment.distance_m} m away &middot; capacity ~${segment.estimated_capacity} &middot; ${duration}</div>
  `;
}

function candidateMarker(segment, highlighted) {
  const color = segment.zone_type === "blue" ? "#1560bd" : "#555";
  return L.circleMarker([segment.lat, segment.lon], {
    radius: highlighted ? 10 : 6,
    color,
    fillColor: color,
    fillOpacity: highlighted ? 0.9 : 0.4,
    weight: highlighted ? 3 : 1,
  }).bindPopup(
    `<strong>${segment.address_label}</strong><br>${segment.zone_type} zone, ${segment.distance_m} m`
  );
}

function renderMap(data) {
  candidateLayer.clearLayers();
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
  if (data.event) {
    const target = data.current ? data.current.address_label : "(none)";
    log(`${data.event} -> current target: ${target}`);
  }

  renderSegment($("current"), data.current);
  renderMap(data);

  const exhausted = data.state === "exhausted";
  $("exhausted").hidden = !exhausted;

  const active = data.state === "searching";
  $("confirm").disabled = !active;
  $("reject").disabled = !active;

  if (data.state === "parked") {
    $("current").innerHTML += "<div><strong>Parked. Session complete.</strong></div>";
  }

  const list = $("upcoming");
  list.innerHTML = "";
  for (const segment of data.upcoming) {
    const li = document.createElement("li");
    li.textContent = `${segment.address_label} (${segment.zone_type}, ${segment.distance_m} m)`;
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
  const data = await api("/api/session", {
    method: "POST",
    body: JSON.stringify({ lat, lon: lng, zone }),
  });
  sessionId = data.session_id;
  $("setup").hidden = true;
  $("session").hidden = false;

  originMarker.dragging.disable();
  youMarker = L.marker([lat, lng], { draggable: true, icon: youIcon })
    .addTo(map)
    .bindTooltip("You (drag to simulate driving)", { direction: "top" });
  youMarker.on("dragend", async () => {
    const pos = youMarker.getLatLng();
    const body = await api(`/api/session/${sessionId}/position`, {
      method: "POST",
      body: JSON.stringify({ lat: pos.lat, lon: pos.lng }),
    });
    render(body);
  });

  log(`Session started (radius ${data.radius_m} m)`);
  render(data);
});

$("confirm").addEventListener("click", async () => {
  const data = await api(`/api/session/${sessionId}/confirm`, { method: "POST" });
  render(data);
});

$("reject").addEventListener("click", async () => {
  const data = await api(`/api/session/${sessionId}/reject`, { method: "POST" });
  render(data);
});

$("expand").addEventListener("click", async () => {
  const data = await api(`/api/session/${sessionId}/expand`, { method: "POST" });
  render(data);
});
