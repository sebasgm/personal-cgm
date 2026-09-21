'use strict';

/*
 * The browser draws; it does not decide.
 *
 * Zone classification, freshness and the statistics all come from the server,
 * where :core already implements and tests them. Re-deriving any of it here would
 * create a second set of thresholds that could quietly disagree with the phone's,
 * on a page showing whether someone is low.
 */

const ZONE_VAR = {
  URGENT_LOW: '--urgent-low',
  LOW: '--low',
  IN_RANGE: '--in-range',
  HIGH: '--high',
  VERY_HIGH: '--very-high',
};
const ZONE_LABEL = {
  VERY_HIGH: 'Very high',
  HIGH: 'High',
  IN_RANGE: 'In range',
  LOW: 'Low',
  URGENT_LOW: 'Urgent low',
};
const ZONE_ORDER = ['VERY_HIGH', 'HIGH', 'IN_RANGE', 'LOW', 'URGENT_LOW'];

const REFRESH_MS = 60_000;

let dashboard = null;
/* Server clock minus browser clock. The age is rendered against the server's
   idea of now, so a laptop with a drifting clock cannot invent freshness. */
let clockOffset = 0;
let timer = null;

const $ = (id) => document.getElementById(id);
const css = (name) => getComputedStyle(document.body).getPropertyValue(name).trim();

// -- session ---------------------------------------------------------------

$('login-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = $('login-button');
  const error = $('login-error');
  button.disabled = true;
  error.hidden = true;

  try {
    const response = await fetch('/api/login', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ email: $('email').value, password: $('password').value }),
    });
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      throw new Error(body.error || 'Sign-in failed');
    }
    $('password').value = '';
    start();
  } catch (e) {
    error.textContent = e.message;
    error.hidden = false;
  } finally {
    button.disabled = false;
  }
});

$('logout').addEventListener('click', async () => {
  await fetch('/api/logout', { method: 'POST' });
  stop();
});

function start() {
  $('login').hidden = true;
  $('app').hidden = false;
  refresh();
  timer = setInterval(refresh, REFRESH_MS);
  // Ticks the age between refreshes: a frozen "2 min ago" over a dead feed is
  // exactly what this page must not show.
  setInterval(renderAge, 1000);
}

function stop() {
  clearInterval(timer);
  dashboard = null;
  $('app').hidden = true;
  $('login').hidden = false;
}

// -- data ------------------------------------------------------------------

async function refresh() {
  const error = $('app-error');
  try {
    const response = await fetch('/api/dashboard');
    if (response.status === 401) return stop();
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      throw new Error(body.error || 'Could not reach LibreLinkUp');
    }
    dashboard = await response.json();
    clockOffset = dashboard.serverTimeMillis - Date.now();
    error.hidden = true;
    render();
  } catch (e) {
    // Keep the last reading on screen — with its age still counting up, which is
    // what tells you it is no longer current.
    error.textContent = e.message;
    error.hidden = false;
  }
}

// -- rendering -------------------------------------------------------------

function render() {
  if (!dashboard) return;
  const d = dashboard;

  const reading = document.querySelector('.reading');
  reading.dataset.freshness = d.freshness;
  reading.style.setProperty('--zone', `var(${ZONE_VAR[d.zone]})`);

  $('value').textContent = d.displayValue;
  $('unit').textContent = d.unit;
  $('trend').textContent = d.trendGlyph;
  $('delta').textContent = d.delta || '';
  renderAge();

  const stats = d.stats;
  document.querySelectorAll('.strip').forEach((s) => (s.dataset.reliable = stats.reliable));
  $('tir').textContent = pct(stats.zones.IN_RANGE);
  $('avg').textContent = stats.mean == null ? '—' : Math.round(stats.mean);
  $('sensor').textContent = d.sensorDay == null ? '—' : `day ${d.sensorDay}/14`;

  $('gmi').textContent = stats.gmi == null ? '—' : `${stats.gmi.toFixed(1)}%`;
  $('a1c').textContent = stats.a1c == null ? '—' : `${stats.a1c.toFixed(1)}%`;

  renderCoverage(d);
  renderZones(stats);
  drawChart(d);
}

function renderAge() {
  if (!dashboard) return;
  const seconds = Math.max(0, Math.round((Date.now() + clockOffset - dashboard.timestampMillis) / 1000));
  const age = seconds < 60
    ? `${seconds} seconds ago`
    : seconds < 120 ? '1 minute ago' : `${Math.floor(seconds / 60)} minutes ago`;

  const suffix = { FRESH: '', AGING: ' · later than usual', STALE: ' · NOT CURRENT' };
  $('age').textContent = age + (suffix[dashboard.freshness] || '');
}

/*
 * The window this page can actually see.
 *
 * LibreLinkUp serves about twelve hours of graph data and nothing older, and this
 * server stores nothing, so every figure on Trends describes that window rather
 * than a week. Saying so is the difference between a limitation and a wrong number.
 */
function renderCoverage(d) {
  const hours = d.history.length ? (d.serverTimeMillis - d.history[0].t) / 3_600_000 : 0;
  const notice = $('coverage');
  notice.dataset.reliable = d.stats.reliable;
  notice.textContent =
    `Based on the last ${hours.toFixed(1)} hours — ${d.stats.readingCount} readings, ` +
    `${pct(d.stats.coverage)} coverage` +
    (d.largestGapMinutes > 5 ? `, largest gap ${d.largestGapMinutes} min.` : '.');

  $('window-note').textContent =
    'LibreLinkUp only serves about twelve hours of history, and this server keeps ' +
    'nothing between restarts. Longer windows live in the phone app, which records ' +
    'its own history as it polls.';
}

function renderZones(stats) {
  $('zones').innerHTML = ZONE_ORDER.map((zone) => {
    const fraction = stats.zones[zone] || 0;
    return `<div class="zone-row">
      <span class="name">${ZONE_LABEL[zone]}</span>
      <span class="bar"><span class="fill" style="width:${(fraction * 100).toFixed(1)}%;
        background:var(${ZONE_VAR[zone]})"></span></span>
      <span class="pct">${pct(fraction)}</span>
    </div>`;
  }).join('');
}

const pct = (fraction) => (fraction == null ? '—' : `${Math.round(fraction * 100)}%`);

// -- chart -----------------------------------------------------------------

/*
 * Same rules as the phone's chart: the in-range band is background, the trace
 * breaks across gaps rather than drawing a straight line through readings that
 * were never taken, and the axis always contains the target band so a flat hour
 * is not magnified into mountains.
 */
function drawChart(d) {
  const canvas = $('chart');
  const ratio = window.devicePixelRatio || 1;
  const width = canvas.clientWidth;
  const height = 280;
  canvas.width = width * ratio;
  canvas.height = height * ratio;

  const ctx = canvas.getContext('2d');
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.clearRect(0, 0, width, height);

  const points = d.history;
  if (points.length < 2) return;

  const gutter = 34;
  const plot = width - gutter;
  const t = d.thresholds;

  const values = points.map((p) => p.v);
  const min = Math.min(...values, t.low) - 15;
  const max = Math.max(...values, t.high) + 15;
  const span = Math.max(max - min, 1);

  const first = points[0].t;
  const last = points[points.length - 1].t;
  const timeSpan = Math.max(last - first, 1);

  const x = (time) => gutter + ((time - first) / timeSpan) * plot;
  const y = (value) => (1 - (value - min) / span) * height;

  ctx.fillStyle = css('--band');
  ctx.fillRect(gutter, y(t.high), plot, Math.max(y(t.low) - y(t.high), 0));

  ctx.strokeStyle = css('--outline');
  ctx.fillStyle = css('--muted');
  ctx.font = '11px system-ui, sans-serif';
  ctx.lineWidth = 1;
  [t.urgentLow, t.low, t.high, t.veryHigh].forEach((level) => {
    if (level <= min || level >= max) return;
    ctx.beginPath();
    ctx.moveTo(gutter, y(level));
    ctx.lineTo(width, y(level));
    ctx.stroke();
    ctx.fillText(String(Math.round(level)), 2, y(level) + 4);
  });

  // Break the line wherever the sensor stopped reporting.
  const GAP_MS = 20 * 60 * 1000;
  ctx.strokeStyle = css('--fg');
  ctx.lineWidth = 2;
  ctx.lineJoin = 'round';
  ctx.beginPath();
  points.forEach((point, index) => {
    const broken = index > 0 && point.t - points[index - 1].t > GAP_MS;
    if (index === 0 || broken) ctx.moveTo(x(point.t), y(point.v));
    else ctx.lineTo(x(point.t), y(point.v));
  });
  ctx.stroke();

  const current = points[points.length - 1];
  ctx.fillStyle = d.freshness === 'STALE' ? css('--muted') : css(ZONE_VAR[d.zone]);
  ctx.beginPath();
  ctx.arc(x(current.t), y(current.v), 5, 0, Math.PI * 2);
  ctx.fill();
}

// -- chrome ----------------------------------------------------------------

document.querySelectorAll('.tabs button[data-view]').forEach((button) => {
  button.addEventListener('click', () => {
    document.querySelectorAll('.tabs button[data-view]')
      .forEach((b) => b.classList.toggle('active', b === button));
    $('view-now').hidden = button.dataset.view !== 'now';
    $('view-trends').hidden = button.dataset.view !== 'trends';
    if (dashboard) drawChart(dashboard);
  });
});

$('palette').addEventListener('change', (event) => {
  document.body.dataset.palette = event.target.value;
  localStorage.setItem('palette', event.target.value);
  if (dashboard) render();
});

document.body.dataset.palette = localStorage.getItem('palette') || 'default';
$('palette').value = document.body.dataset.palette;

window.addEventListener('resize', () => dashboard && drawChart(dashboard));

// An existing session survives a reload, so try before showing the login form.
fetch('/api/dashboard').then((r) => { if (r.ok) start(); });
