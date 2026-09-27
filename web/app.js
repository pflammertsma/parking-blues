let sessionId = null;

const $ = (id) => document.getElementById(id);

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

function render(data) {
  if (data.event) {
    const target = data.current ? data.current.address_label : "(none)";
    log(`${data.event} -> current target: ${target}`);
  }

  renderSegment($("current"), data.current);

  const exhausted = data.state === "exhausted";
  $("exhausted").hidden = !exhausted;

  const active = data.state === "searching";
  $("confirm").disabled = !active;
  $("reject").disabled = !active;
  $("send-position").disabled = !active;

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
      $("lat").value = pos.coords.latitude.toFixed(5);
      $("lon").value = pos.coords.longitude.toFixed(5);
    },
    (err) => log(`Geolocation failed: ${err.message}`)
  );
});

$("start").addEventListener("click", async () => {
  const lat = parseFloat($("lat").value);
  const lon = parseFloat($("lon").value);
  const zone = selectedZone();
  const data = await api("/api/session", {
    method: "POST",
    body: JSON.stringify({ lat, lon, zone }),
  });
  sessionId = data.session_id;
  $("setup").hidden = true;
  $("session").hidden = false;
  $("sim-lat").value = lat.toFixed(5);
  $("sim-lon").value = lon.toFixed(5);
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

$("send-position").addEventListener("click", async () => {
  const lat = parseFloat($("sim-lat").value);
  const lon = parseFloat($("sim-lon").value);
  const data = await api(`/api/session/${sessionId}/position`, {
    method: "POST",
    body: JSON.stringify({ lat, lon }),
  });
  render(data);
});
