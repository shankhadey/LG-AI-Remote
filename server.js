// Home bridge: serves the phone remote and relays button presses to the TV.
// Run it on any always-on machine on the same Wi-Fi as the TV, then open
// http://<this-machine's-ip>:8080 on each phone and "Add to Home Screen".
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { discover } from './lib/ssdp.js';
import { wake } from './lib/wol.js';
import { RokuDriver } from './lib/drivers/roku.js';
import { SamsungDriver } from './lib/drivers/samsung.js';
import { LgDriver } from './lib/drivers/lg.js';
import { SonyDriver } from './lib/drivers/sony.js';

const DIR = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT || 8080);
const CONFIG = process.env.CONFIG || path.join(DIR, 'config.json');
const PIN = process.env.REMOTE_PIN || ''; // optional: require a PIN from phones

export const DRIVERS = { roku: RokuDriver, samsung: SamsungDriver, lg: LgDriver, sony: SonyDriver };
const UNSUPPORTED = {
  androidtv: 'Android / Google TV uses an encrypted pairing protocol that this bridge does not speak yet.',
};

// ---- config ---------------------------------------------------------------
export function loadConfig(file = CONFIG) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return {}; }
}
function saveConfig(cfg, file = CONFIG) { fs.writeFileSync(file, JSON.stringify(cfg, null, 2)); }

export function createBridge({ configFile = CONFIG, driverOptions = {}, discoverFn = discover, wakeFn = wake } = {}) {
  let cfg = loadConfig(configFile);
  let driver = null;
  let lastScan = [];

  const store = {
    get: (k) => cfg.tv?.secrets?.[k],
    set: (k, v) => { cfg.tv.secrets = { ...cfg.tv.secrets, [k]: v }; saveConfig(cfg, configFile); },
  };
  function getDriver() {
    if (!cfg.tv) throw httpError(409, 'No TV chosen yet');
    if (!driver) {
      const D = DRIVERS[cfg.tv.brand];
      if (!D) throw httpError(400, UNSUPPORTED[cfg.tv.brand] || `Unsupported TV type: ${cfg.tv.brand}`);
      driver = new D(cfg.tv, store, driverOptions[cfg.tv.brand]);
    }
    return driver;
  }
  function state() {
    const d = cfg.tv && DRIVERS[cfg.tv.brand] ? getDriver() : null;
    return { tv: cfg.tv ? { ...cfg.tv, secrets: undefined, paired: !!Object.keys(cfg.tv.secrets || {}).length } : null, capabilities: d?.capabilities || null, pinRequired: !!PIN };
  }

  const routes = {
    'GET /api/state': async () => state(),
    'POST /api/discover': async () => {
      lastScan = (await discoverFn()).map((d) => ({ ...d, supported: !!DRIVERS[d.brand], note: UNSUPPORTED[d.brand] }));
      return lastScan;
    },
    'POST /api/select': async (b) => {
      const dev = lastScan.find((d) => d.id === b.id) || (b.ip && b.brand ? { id: b.ip, ip: b.ip, brand: b.brand, name: b.name || b.ip } : null);
      if (!dev) throw httpError(404, 'Device not found — scan again');
      if (!DRIVERS[dev.brand]) throw httpError(400, UNSUPPORTED[dev.brand] || 'Not a supported TV');
      driver?.close?.();
      driver = null;
      cfg.tv = { id: dev.id, ip: dev.ip, brand: dev.brand, name: dev.name, model: dev.model, mac: b.mac || '', secrets: b.psk ? { psk: b.psk } : {} };
      saveConfig(cfg, configFile);
      return state();
    },
    'POST /api/pair': async () => {
      const info = await getDriver().connect();
      if (info?.mac && !cfg.tv.mac) cfg.tv.mac = info.mac;
      if (info?.model && !cfg.tv.model) cfg.tv.model = info.model;
      saveConfig(cfg, configFile);
      return { ...state(), info };
    },
    'POST /api/settings': async (b) => {
      if (!cfg.tv) throw httpError(409, 'No TV chosen yet');
      if (typeof b.mac === 'string') cfg.tv.mac = b.mac;
      if (typeof b.name === 'string' && b.name) cfg.tv.name = b.name;
      if (typeof b.psk === 'string') cfg.tv.secrets = { ...cfg.tv.secrets, psk: b.psk };
      saveConfig(cfg, configFile);
      return state();
    },
    'POST /api/forget': async () => { driver?.close?.(); driver = null; delete cfg.tv; saveConfig(cfg, configFile); return state(); },
    'POST /api/key': async (b) => {
      const d = getDriver();
      if (b.key === 'POWER_ON' && cfg.tv.brand !== 'roku' && cfg.tv.brand !== 'sony') {
        await wakeFn(cfg.tv.mac);
        return { ok: true };
      }
      await d.key(String(b.key));
      return { ok: true };
    },
    'GET /api/apps': async () => getDriver().apps(),
    'POST /api/launch': async (b) => { await getDriver().launch(String(b.id)); return { ok: true }; },
    'POST /api/text': async (b) => { await getDriver().text(String(b.text || '')); return { ok: true }; },
    'GET /api/info': async () => getDriver().info(),
  };

  async function handle(req, res) {
    const url = new URL(req.url, 'http://x');
    if (url.pathname.startsWith('/api/')) {
      if (PIN && req.headers['x-remote-pin'] !== PIN) return json(res, 401, { error: 'PIN required' });
      if (url.pathname.startsWith('/api/roku-icon/')) {
        try {
          const icon = await getDriver().icon(decodeURIComponent(url.pathname.slice(15)));
          res.writeHead(200, { 'Content-Type': icon.type, 'Cache-Control': 'max-age=86400' });
          return res.end(icon.body);
        } catch { res.writeHead(404); return res.end(); }
      }
      const fn = routes[`${req.method} ${url.pathname}`];
      if (!fn) return json(res, 404, { error: 'Not found' });
      try {
        const body = req.method === 'POST' ? await readJson(req) : {};
        return json(res, 200, await fn(body));
      } catch (e) {
        if (e.status) return json(res, e.status, { error: e.message });
        // A dropped connection to the TV: reset so the next press reconnects.
        if (driver && /ECONNREFUSED|ECONNRESET|EHOSTUNREACH|ETIMEDOUT|closed|not opened/i.test(`${e.code} ${e.message}`)) { driver.close?.(); driver = null; }
        return json(res, 502, { error: friendly(e) });
      }
    }
    return serveStatic(url.pathname, res);
  }
  return { handle, state };
}

function friendly(e) {
  const code = e.code || e.cause?.code;
  if (code === 'ECONNREFUSED' || code === 'EHOSTUNREACH' || e.name === 'TimeoutError' || code === 'UND_ERR_CONNECT_TIMEOUT') return 'Can\'t reach the TV — is it on and on the same network?';
  return e.message || String(e);
}
function httpError(status, message) { return Object.assign(new Error(message), { status }); }
function json(res, status, obj) { res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(obj)); }
function readJson(req) {
  return new Promise((resolve, reject) => {
    let s = '';
    req.on('data', (c) => { s += c; if (s.length > 1e5) req.destroy(); });
    req.on('end', () => { try { resolve(s ? JSON.parse(s) : {}); } catch { reject(httpError(400, 'Bad JSON')); } });
  });
}
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.svg': 'image/svg+xml', '.png': 'image/png' };
function serveStatic(p, res) {
  const pub = path.join(DIR, 'public');
  const file = path.normalize(path.join(pub, p === '/' ? 'index.html' : p));
  if (!file.startsWith(pub)) { res.writeHead(403); return res.end(); }
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404); return res.end('Not found'); }
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
    res.end(data);
  });
}

function lanAddresses() {
  return Object.values(os.networkInterfaces()).flat().filter((i) => i && i.family === 'IPv4' && !i.internal).map((i) => i.address);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const bridge = createBridge();
  http.createServer(bridge.handle).listen(PORT, '0.0.0.0', () => {
    console.log('TV remote bridge running. On each phone (same Wi-Fi), open:');
    for (const a of lanAddresses()) console.log(`  http://${a}:${PORT}`);
    if (PIN) console.log('PIN protection is on.');
  });
}
