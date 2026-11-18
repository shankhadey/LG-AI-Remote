// Roku / Roku TV via External Control Protocol (HTTP, port 8060). No pairing needed.
// Note: "Control by mobile apps" must be set to Enabled/Permissive in Settings > System > Advanced.
const MAP = {
  POWER: 'Power', POWER_ON: 'PowerOn', VOL_UP: 'VolumeUp', VOL_DOWN: 'VolumeDown', MUTE: 'VolumeMute',
  CH_UP: 'ChannelUp', CH_DOWN: 'ChannelDown', UP: 'Up', DOWN: 'Down', LEFT: 'Left', RIGHT: 'Right',
  OK: 'Select', BACK: 'Back', HOME: 'Home', MENU: 'Info', INFO: 'Info', INPUT: 'InputHDMI1',
  PLAY: 'Play', PAUSE: 'Play', PLAY_PAUSE: 'Play', REW: 'Rev', FF: 'Fwd', STOP: 'Back',
};

export class RokuDriver {
  constructor(device, store, { base } = {}) {
    this.device = device;
    this.store = store;
    this.base = base || `http://${device.ip}:8060`;
  }
  get capabilities() {
    return { keys: Object.keys(MAP), apps: true, text: true, powerOn: true, inputs: ['InputHDMI1', 'InputHDMI2', 'InputHDMI3', 'InputHDMI4', 'InputTuner', 'InputAV1'] };
  }
  async post(path) {
    const r = await fetch(this.base + path, { method: 'POST', signal: AbortSignal.timeout(4000) });
    if (!r.ok) throw new Error(`Roku said ${r.status}${r.status === 403 ? ' — enable "Control by mobile apps" on the TV' : ''}`);
  }
  async get(path) {
    const r = await fetch(this.base + path, { signal: AbortSignal.timeout(4000) });
    if (!r.ok) throw new Error(`Roku said ${r.status}`);
    return r.text();
  }
  async connect() { return this.info(); }
  async info() {
    const xml = await this.get('/query/device-info');
    const t = (n) => (xml.match(new RegExp(`<${n}>([^<]*)</${n}>`)) || [])[1] || '';
    return { name: t('user-device-name') || t('friendly-device-name'), model: `${t('vendor-name')} ${t('model-name')}`.trim(), power: t('power-mode'), mac: t('wifi-mac') || t('ethernet-mac') };
  }
  async key(k) {
    if (k.startsWith('NUM_')) return this.post(`/keypress/Lit_${k.slice(4)}`);
    if (k.startsWith('Input')) return this.post(`/keypress/${k}`);
    const code = MAP[k];
    if (!code) throw new Error(`Roku has no ${k} button`);
    return this.post(`/keypress/${code}`);
  }
  async apps() {
    const xml = await this.get('/query/apps');
    return [...xml.matchAll(/<app id="([^"]+)"[^>]*>([^<]*)<\/app>/g)].map(([, id, name]) => ({
      id, name: name.replace(/&amp;/g, '&'), icon: `/api/roku-icon/${encodeURIComponent(id)}`,
    }));
  }
  async launch(id) { return this.post(`/launch/${encodeURIComponent(id)}`); }
  async text(s) { for (const ch of s) await this.post(`/keypress/Lit_${encodeURIComponent(ch)}`); }
  async icon(id) {
    const r = await fetch(`${this.base}/query/icon/${encodeURIComponent(id)}`, { signal: AbortSignal.timeout(4000) });
    return { type: r.headers.get('content-type') || 'image/png', body: Buffer.from(await r.arrayBuffer()) };
  }
}
