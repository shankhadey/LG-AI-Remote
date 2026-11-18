// Phone remote UI. Talks only to the home bridge (same origin), never to the TV directly.
const $ = (s) => document.querySelector(s);
const $$ = (s) => [...document.querySelectorAll(s)];
let state = null;
let appsLoaded = false;

const store = {
  get(k) { try { return localStorage.getItem(k) || ''; } catch { return ''; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch {} },
};

async function api(path, body) {
  const r = await fetch(path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Remote-Pin': store.get('pin') },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(data.error || `Error ${r.status}`);
  return data;
}

let toastTimer;
function toast(msg, isErr = false) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.toggle('err', isErr);
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), isErr ? 4000 : 1800);
}
const buzz = () => { try { navigator.vibrate?.(12); } catch {} };
const setDot = (cls) => { $('#dot').className = `dot ${cls || ''}`; };

// ---- button presses (with hold-to-repeat) ---------------------------------
let inflight = Promise.resolve();
function press(key) {
  buzz();
  // Queue presses so they reach the TV in order.
  inflight = inflight.then(() => api('/api/key', { key }))
    .then(() => setDot('on'))
    .catch((e) => { setDot('err'); toast(e.message, true); });
  return inflight;
}

function bindKeys() {
  for (const btn of $$('[data-key]')) {
    let timer, rep;
    const stop = () => { clearTimeout(timer); clearInterval(rep); btn.classList.remove('pressed'); };
    btn.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      btn.classList.add('pressed');
      press(btn.dataset.key);
      if (btn.hasAttribute('data-repeat')) {
        timer = setTimeout(() => { rep = setInterval(() => press(btn.dataset.key), 160); }, 450);
      }
    });
    for (const ev of ['pointerup', 'pointerleave', 'pointercancel']) btn.addEventListener(ev, stop);
    btn.addEventListener('contextmenu', (e) => e.preventDefault());
  }
}

// ---- screens --------------------------------------------------------------
function applyCapabilities(caps) {
  const keys = new Set(caps?.keys || []);
  for (const btn of $$('#remote [data-key]')) btn.style.visibility = keys.has(btn.dataset.key) ? '' : 'hidden';
  $('[data-goto="apps"]').hidden = !caps?.apps;
  $('[data-goto="type"]').hidden = !caps?.text;
  $('.tabs').style.gridTemplateColumns = `repeat(${$$('.tabs button:not([hidden])').length}, 1fr)`;
}

function render() {
  const tv = state?.tv;
  const ready = tv && state.capabilities;
  $('#setup').hidden = !!ready;
  $('#remote').hidden = !ready;
  $('#tvName').textContent = tv?.name || 'TV Remote';
  if (ready) applyCapabilities(state.capabilities);
}

function showTab(name) {
  for (const s of $$('.tab')) s.hidden = s.dataset.tab !== name;
  for (const b of $$('.tabs button')) b.classList.toggle('on', b.dataset.goto === name);
  if (name === 'apps' && !appsLoaded) loadApps();
  if (name === 'type') setTimeout(() => $('#typeInput').focus(), 50);
}

async function loadApps() {
  const box = $('#apps');
  box.innerHTML = '<p class="muted">Loading apps…</p>';
  try {
    const apps = await api('/api/apps');
    box.innerHTML = '';
    if (!apps.length) box.innerHTML = '<p class="muted">The TV didn\'t list any apps.</p>';
    for (const a of apps.sort((x, y) => x.name.localeCompare(y.name))) {
      const b = document.createElement('button');
      b.className = 'app';
      const img = a.icon ? Object.assign(document.createElement('img'), { src: a.icon, alt: '', loading: 'lazy' }) : null;
      const ph = Object.assign(document.createElement('div'), { className: 'ph', textContent: (a.name || '?')[0] });
      if (img) img.onerror = () => img.replaceWith(ph);
      b.append(img || ph, Object.assign(document.createElement('span'), { textContent: a.name }));
      b.onclick = () => { buzz(); api('/api/launch', { id: a.id }).then(() => toast(`Opening ${a.name}`)).catch((e) => toast(e.message, true)); };
      box.append(b);
    }
    appsLoaded = true;
  } catch (e) {
    box.innerHTML = '';
    const p = Object.assign(document.createElement('p'), { className: 'muted', textContent: e.message });
    const retry = Object.assign(document.createElement('button'), { textContent: 'Try again', onclick: loadApps });
    box.append(p, retry);
  }
}

// ---- setup / pairing ------------------------------------------------------
const BRAND_HELP = {
  samsung: 'Tap Connect, then press "Allow" on the TV with the physical remote (only needed once).',
  lg: 'Tap Connect, then accept the pairing prompt on the TV (only needed once).',
  roku: 'Roku needs no pairing. If it fails, set Settings › System › Advanced system settings › Control by mobile apps to "Enabled".',
  sony: 'On the TV: Settings › Network › Home network › IP control › Authentication = "Normal and Pre-Shared Key", and choose a key. Enter that key below.',
};
const BRAND_NAME = { samsung: 'Samsung', lg: 'LG', roku: 'Roku', sony: 'Sony', androidtv: 'Google / Android TV' };

async function scan() {
  const list = $('#devices');
  $('#scanBtn').disabled = true;
  $('#scanBtn').textContent = 'Searching…';
  list.innerHTML = '';
  try {
    const devs = await api('/api/discover', {});
    if (!devs.length) list.innerHTML = '<li class="muted">Nothing answered. Check the TV is on, or enter it by hand below.</li>';
    for (const d of devs) {
      const li = document.createElement('li');
      const b = document.createElement('button');
      b.disabled = !d.supported;
      const sub = d.supported ? `${BRAND_NAME[d.brand]} · ${d.model || d.ip}` : (d.note || `${d.manufacturer || 'Unknown device'} · ${d.ip}`);
      b.append(Object.assign(document.createElement('b'), { textContent: d.name }), Object.assign(document.createElement('small'), { textContent: sub }));
      b.onclick = () => choose({ id: d.id }, d.brand);
      li.append(b);
      list.append(li);
    }
  } catch (e) { toast(e.message, true); }
  $('#scanBtn').disabled = false;
  $('#scanBtn').textContent = 'Search again';
}

async function choose(sel, brand) {
  try {
    state = await api('/api/select', sel);
    showPair(brand);
  } catch (e) { toast(e.message, true); }
}

function showPair(brand) {
  $('#pairBox').hidden = false;
  $('#pairTitle').textContent = `Connect to ${state.tv.name}`;
  $('#pairHelp').textContent = BRAND_HELP[brand] || '';
  $('#pskRow').hidden = brand !== 'sony';
  $('#pairBox').scrollIntoView({ behavior: 'smooth' });
}

async function pair() {
  const btn = $('#pairBtn');
  btn.disabled = true;
  btn.textContent = 'Waiting for the TV…';
  try {
    if (state.tv.brand === 'sony') await api('/api/settings', { psk: $('#pskInput').value.trim() });
    state = await api('/api/pair', {});
    toast('Connected');
    setDot('on');
    render();
  } catch (e) { toast(e.message, true); }
  btn.disabled = false;
  btn.textContent = 'Connect';
}

// ---- settings -------------------------------------------------------------
function openSettings() {
  const tv = state?.tv;
  $('#tvDetails').textContent = tv ? `${BRAND_NAME[tv.brand] || tv.brand} ${tv.model || ''} · ${tv.ip}` : 'No TV connected yet.';
  $('#setName').value = tv?.name || '';
  $('#setMac').value = tv?.mac || '';
  $('#setPskRow').hidden = tv?.brand !== 'sony';
  $('#setPin').value = store.get('pin');
  for (const id of ['#powerOnBtn', '#repairBtn', '#forgetBtn']) $(id).hidden = !tv;
  $('#settings').showModal();
}

async function saveSettings() {
  store.set('pin', $('#setPin').value.trim());
  if (!state?.tv) return init();
  try {
    const body = { name: $('#setName').value.trim(), mac: $('#setMac').value.trim() };
    if (state.tv.brand === 'sony' && $('#setPsk').value) body.psk = $('#setPsk').value.trim();
    state = await api('/api/settings', body);
    render();
    toast('Saved');
  } catch (e) { toast(e.message, true); }
}

// ---- boot -----------------------------------------------------------------
async function init() {
  try {
    state = await api('/api/state');
    render();
    if (state.tv && state.capabilities && !state.tv.paired && state.tv.brand !== 'roku') showPairFromRemote();
  } catch (e) {
    toast(e.message, true);
    if (/PIN/.test(e.message)) openSettings();
  }
}
function showPairFromRemote() { $('#setup').hidden = false; $('#remote').hidden = true; showPair(state.tv.brand); }

bindKeys();
$('#scanBtn').onclick = scan;
$('#pairBtn').onclick = pair;
$('#manBtn').onclick = () => choose({ ip: $('#manIp').value.trim(), brand: $('#manBrand').value }, $('#manBrand').value);
$('#settingsBtn').onclick = openSettings;
$('#saveSettings').onclick = saveSettings;
$('#powerOnBtn').onclick = () => { $('#settings').close(); press('POWER_ON').then(() => toast('Wake signal sent')); };
$('#repairBtn').onclick = () => { $('#settings').close(); showPairFromRemote(); };
$('#forgetBtn').onclick = async () => {
  if (!confirm('Forget this TV? You will need to pair again.')) return;
  $('#settings').close();
  state = await api('/api/forget', {});
  appsLoaded = false;
  render();
};
for (const b of $$('.tabs button')) b.onclick = () => showTab(b.dataset.goto);
$('#typeForm').onsubmit = (e) => {
  e.preventDefault();
  const v = $('#typeInput').value;
  if (!v) return;
  api('/api/text', { text: v }).then(() => { toast('Sent'); $('#typeInput').value = ''; }).catch((err) => toast(err.message, true));
};
document.addEventListener('visibilitychange', () => { if (!document.hidden) init(); });
init();
