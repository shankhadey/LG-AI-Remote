// Sony Bravia via its REST API + IRCC. Needs a Pre-Shared Key set on the TV:
// Settings > Network > Home network > IP control > Authentication: "Normal and Pre-Shared Key".
const NAMES = {
  POWER: 'PowerOff', VOL_UP: 'VolumeUp', VOL_DOWN: 'VolumeDown', MUTE: 'Mute', CH_UP: 'ChannelUp', CH_DOWN: 'ChannelDown',
  UP: 'Up', DOWN: 'Down', LEFT: 'Left', RIGHT: 'Right', OK: 'Confirm', BACK: 'Return', HOME: 'Home',
  MENU: 'ActionMenu', INFO: 'Display', INPUT: 'Input', PLAY: 'Play', PAUSE: 'Pause', STOP: 'Stop', REW: 'Rewind', FF: 'Forward',
  ...Object.fromEntries(Array.from({ length: 10 }, (_, i) => [`NUM_${i}`, `Num${i}`])),
};

export class SonyDriver {
  constructor(device, store, { base } = {}) {
    this.device = device;
    this.store = store;
    this.base = base || `http://${device.ip}`;
    this.codes = null;
  }
  get capabilities() {
    return { keys: [...Object.keys(NAMES), 'PLAY_PAUSE', 'POWER_ON'], apps: true, text: true, powerOn: true, needsPsk: true };
  }
  get psk() { return this.store.get('psk') || ''; }
  async rpc(service, method, params = [], version = '1.0') {
    const r = await fetch(`${this.base}/sony/${service}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Auth-PSK': this.psk },
      body: JSON.stringify({ method, id: 1, params, version }), signal: AbortSignal.timeout(4000),
    });
    const j = await r.json();
    if (j.error) throw new Error(j.error[0] === 403 || j.error[0] === 40005 ? 'TV rejected the Pre-Shared Key — check it in Settings' : `Sony error: ${j.error[1] || j.error[0]}`);
    return j.result;
  }
  async loadCodes() {
    if (this.codes) return this.codes;
    const res = await this.rpc('system', 'getRemoteControllerInfo');
    this.codes = Object.fromEntries((res?.[1] || []).map((c) => [c.name, c.value]));
    return this.codes;
  }
  async ircc(code) {
    const body = `<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:X_SendIRCC xmlns:u="urn:schemas-sony-com:service:IRCC:1"><IRCCCode>${code}</IRCCCode></u:X_SendIRCC></s:Body></s:Envelope>`;
    const r = await fetch(`${this.base}/sony/IRCC`, {
      method: 'POST', body, signal: AbortSignal.timeout(4000),
      headers: { 'Content-Type': 'text/xml; charset=UTF-8', SOAPACTION: '"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC"', 'X-Auth-PSK': this.psk },
    });
    if (!r.ok) throw new Error(r.status === 403 ? 'TV rejected the Pre-Shared Key' : `Sony said ${r.status}`);
  }
  async connect() { await this.loadCodes(); return this.info(); }
  async info() {
    const [s] = await this.rpc('system', 'getSystemInformation').catch(() => [{}]);
    return { name: s?.name, model: s?.model, mac: s?.macAddr };
  }
  async key(k) {
    if (k === 'POWER_ON') return this.rpc('system', 'setPowerStatus', [{ status: true }]);
    if (k === 'POWER') {
      const [p] = await this.rpc('system', 'getPowerStatus');
      return this.rpc('system', 'setPowerStatus', [{ status: p?.status !== 'active' }]);
    }
    const codes = await this.loadCodes();
    if (k === 'PLAY_PAUSE') { this.playing = !this.playing; return this.ircc(codes[this.playing ? 'Play' : 'Pause']); }
    const code = codes[NAMES[k]];
    if (!code) throw new Error(`This Sony has no ${k} button`);
    return this.ircc(code);
  }
  async apps() {
    const [list] = await this.rpc('appControl', 'getApplicationList');
    return (list || []).map((a) => ({ id: a.uri, name: a.title, icon: a.icon || null }));
  }
  async launch(uri) { await this.rpc('appControl', 'setActiveApp', [{ uri }]); }
  async text(s) { await this.rpc('appControl', 'setTextForm', [s]); }
}
