// Samsung Tizen TVs (2016+) via the remote-control WebSocket on port 8002.
// First connection shows an "Allow" prompt on the TV; the token it returns is saved.
import WebSocket from 'ws';

const MAP = {
  POWER: 'KEY_POWER', VOL_UP: 'KEY_VOLUP', VOL_DOWN: 'KEY_VOLDOWN', MUTE: 'KEY_MUTE',
  CH_UP: 'KEY_CHUP', CH_DOWN: 'KEY_CHDOWN', UP: 'KEY_UP', DOWN: 'KEY_DOWN', LEFT: 'KEY_LEFT', RIGHT: 'KEY_RIGHT',
  OK: 'KEY_ENTER', BACK: 'KEY_RETURN', HOME: 'KEY_HOME', MENU: 'KEY_MENU', INFO: 'KEY_INFO', INPUT: 'KEY_SOURCE',
  PLAY: 'KEY_PLAY', PAUSE: 'KEY_PAUSE', PLAY_PAUSE: null, STOP: 'KEY_STOP', REW: 'KEY_REWIND', FF: 'KEY_FF',
  ...Object.fromEntries(Array.from({ length: 10 }, (_, i) => [`NUM_${i}`, `KEY_${i}`])),
};
const APP_NAME = Buffer.from('Phone Remote').toString('base64');

export class SamsungDriver {
  constructor(device, store, { wsBase, httpBase } = {}) {
    this.device = device;
    this.store = store;
    this.wsBase = wsBase || `wss://${device.ip}:8002`;
    this.httpBase = httpBase || `http://${device.ip}:8001`;
    this.ws = null;
    this.waiters = new Map();
    this.playing = false;
  }
  get capabilities() {
    return { keys: [...Object.keys(MAP), 'POWER_ON'], apps: true, text: true, powerOn: true };
  }
  async open() {
    if (this.ws?.readyState === WebSocket.OPEN) return this.ws;
    const token = this.store.get('token');
    const url = `${this.wsBase}/api/v2/channels/samsung.remote.control?name=${APP_NAME}${token ? `&token=${token}` : ''}`;
    const ws = new WebSocket(url, { rejectUnauthorized: false, handshakeTimeout: 5000 });
    await new Promise((resolve, reject) => {
      // 30s so someone can walk over and press Allow on the TV the first time.
      const t = setTimeout(() => { ws.terminate(); reject(new Error('No answer from the TV. If an "Allow" prompt is showing, accept it and try again.')); }, 30000);
      ws.on('message', (raw) => {
        let msg; try { msg = JSON.parse(raw); } catch { return; }
        if (msg.event === 'ms.channel.connect') {
          clearTimeout(t);
          if (msg.data?.token) this.store.set('token', msg.data.token);
          resolve();
        } else if (msg.event === 'ms.channel.unauthorized') {
          clearTimeout(t); ws.close(); reject(new Error('The TV refused the connection. Allow "Phone Remote" under Settings > General > External Device Manager > Device Connection Manager.'));
        } else if (this.waiters.has(msg.event)) {
          this.waiters.get(msg.event)(msg); this.waiters.delete(msg.event);
        }
      });
      ws.on('error', (e) => { clearTimeout(t); reject(e); });
      ws.on('close', () => { if (this.ws === ws) this.ws = null; });
    });
    this.ws = ws;
    return ws;
  }
  async send(obj) { (await this.open()).send(JSON.stringify(obj)); }
  request(obj, event, ms = 5000) {
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { this.waiters.delete(event); reject(new Error('TV did not answer')); }, ms);
      this.waiters.set(event, (m) => { clearTimeout(t); resolve(m); });
      this.send(obj).catch((e) => { clearTimeout(t); reject(e); });
    });
  }
  async connect() { await this.open(); return this.info(); }
  async info() {
    try {
      const r = await fetch(`${this.httpBase}/api/v2/`, { signal: AbortSignal.timeout(3000) });
      const d = (await r.json()).device || {};
      return { name: d.name, model: d.modelName, power: d.PowerState, mac: d.wifiMac };
    } catch { return {}; }
  }
  async key(k) {
    let code = MAP[k];
    if (k === 'PLAY_PAUSE') { code = this.playing ? 'KEY_PAUSE' : 'KEY_PLAY'; this.playing = !this.playing; }
    if (!code) throw new Error(`Samsung has no ${k} button`);
    await this.send({ method: 'ms.remote.control', params: { Cmd: 'Click', DataOfCmd: code, Option: 'false', TypeOfRemote: 'SendRemoteKey' } });
  }
  async apps() {
    const m = await this.request({ method: 'ms.channel.emit', params: { event: 'ed.installedApp.get', to: 'host' } }, 'ed.installedApp.get');
    this.appTypes = {};
    return (m.data?.data || []).map((a) => {
      this.appTypes[a.appId] = a.app_type;
      return { id: a.appId, name: a.name, icon: null };
    });
  }
  async launch(id) {
    const type = this.appTypes?.[id] === 2 ? 'DEEP_LINK' : 'NATIVE_LAUNCH';
    await this.send({ method: 'ms.channel.emit', params: { event: 'ed.apps.launch', to: 'host', data: { appId: id, action_type: type } } });
  }
  async text(s) {
    // Only works while an on-screen keyboard is open on the TV.
    await this.send({ method: 'ms.remote.control', params: { Cmd: Buffer.from(s).toString('base64'), DataOfCmd: 'base64', TypeOfRemote: 'SendInputString' } });
    await this.send({ method: 'ms.remote.control', params: { TypeOfRemote: 'SendInputEnd' } });
  }
  close() { this.ws?.close(); }
}
