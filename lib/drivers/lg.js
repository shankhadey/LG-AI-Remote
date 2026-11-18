// LG webOS TVs via the SSAP WebSocket (wss://tv:3001). First connection shows a pairing
// prompt on the TV; the client-key it returns is saved. Arrow/OK/Back go over the
// "pointer input" socket the TV hands out.
import WebSocket from 'ws';

const SSAP = {
  VOL_UP: 'ssap://audio/volumeUp', VOL_DOWN: 'ssap://audio/volumeDown',
  CH_UP: 'ssap://tv/channelUp', CH_DOWN: 'ssap://tv/channelDown',
  PLAY: 'ssap://media.controls/play', PAUSE: 'ssap://media.controls/pause', STOP: 'ssap://media.controls/stop',
  REW: 'ssap://media.controls/rewind', FF: 'ssap://media.controls/fastForward',
  POWER: 'ssap://system/turnOff',
};
const BUTTONS = {
  UP: 'UP', DOWN: 'DOWN', LEFT: 'LEFT', RIGHT: 'RIGHT', OK: 'ENTER', BACK: 'BACK', HOME: 'HOME',
  MENU: 'MENU', INFO: 'INFO', INPUT: null,
  ...Object.fromEntries(Array.from({ length: 10 }, (_, i) => [`NUM_${i}`, String(i)])),
};

// Standard permission manifest used by LG's own second-screen clients.
const MANIFEST = {
  manifestVersion: 1,
  appVersion: '1.1',
  signed: {
    created: '20140509', appId: 'com.lge.test', vendorId: 'com.lge', localizedAppNames: { '': 'Phone Remote' },
    localizedVendorNames: { '': 'LG Electronics' }, serial: '2f930e2d2cfe083771f68e4fe7bb07',
    permissions: ['TEST_SECURE', 'CONTROL_INPUT_TEXT', 'CONTROL_MOUSE_AND_KEYBOARD', 'READ_INSTALLED_APPS', 'READ_LGE_SDX', 'READ_NOTIFICATIONS', 'SEARCH', 'WRITE_SETTINGS', 'WRITE_NOTIFICATION_ALERT', 'CONTROL_POWER', 'READ_CURRENT_CHANNEL', 'READ_RUNNING_APPS', 'READ_UPDATE_INFO', 'UPDATE_FROM_REMOTE_APP', 'READ_LGE_TV_INPUT_EVENTS', 'READ_TV_CURRENT_TIME'],
  },
  permissions: ['LAUNCH', 'LAUNCH_WEBAPP', 'APP_TO_APP', 'CLOSE', 'TEST_OPEN', 'TEST_PROTECTED', 'CONTROL_AUDIO', 'CONTROL_DISPLAY', 'CONTROL_INPUT_JOYSTICK', 'CONTROL_INPUT_MEDIA_RECORDING', 'CONTROL_INPUT_MEDIA_PLAYBACK', 'CONTROL_INPUT_TV', 'CONTROL_POWER', 'READ_APP_STATUS', 'READ_CURRENT_CHANNEL', 'READ_INPUT_DEVICE_LIST', 'READ_NETWORK_STATE', 'READ_RUNNING_APPS', 'READ_TV_CHANNEL_LIST', 'WRITE_NOTIFICATION_TOAST', 'READ_POWER_STATE', 'READ_COUNTRY_INFO', 'READ_SETTINGS', 'CONTROL_TV_SCREEN', 'CONTROL_TV_STANBY', 'CONTROL_FAVORITE_GROUP', 'CONTROL_USER_INFO', 'CHECK_BLUETOOTH_DEVICE', 'CONTROL_BLUETOOTH', 'CONTROL_TIMER_INFO', 'STB_INTERNAL_CONNECTION', 'CONTROL_RECORDING', 'READ_RECORDING_STATE', 'WRITE_RECORDING_LIST', 'READ_RECORDING_LIST', 'READ_RECORDING_SCHEDULE', 'WRITE_RECORDING_SCHEDULE', 'READ_STORAGE_DEVICE_LIST', 'READ_TV_PROGRAM_INFO', 'CONTROL_BOX_CHANNEL', 'READ_TV_ACR_AUTH_TOKEN', 'READ_TV_CONTENT_STATE', 'READ_TV_CURRENT_TIME', 'ADD_LAUNCHER_CHANNEL', 'SET_CHANNEL_SKIP', 'RELEASE_CHANNEL_SKIP', 'CONTROL_CHANNEL_BLOCK', 'DELETE_SELECT_CHANNEL', 'CONTROL_CHANNEL_GROUP', 'SCAN_TV_CHANNELS', 'CONTROL_TV_POWER', 'CONTROL_WOL'],
  signatures: [{ signatureVersion: 1, signature: 'eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbmctY2VydCIsInNpZ25hdHVyZVZlcnNpb24iOjF9.hrVRgjCwXVvE2OOSpDZ58hR+59aFNwYDyjQgKk3auukd7pcegmE2CzPCa0bJ0ZsRAcKkCTJrWo5iDzNhMBWRyaMOv5zWSrthlf7G128qvIlpMT0YNY+n/FaOHE73uLrS/g7swl3/qH/BGFG2Hu4RlL48eb3lLKqTt2xKHdCs6Cd4RMfJPYnzgvI4BNrFUKsjkcu+WD4OO2A27Pq1n50cMchmcaXadJhGrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqaFM2DXzdKX9NmmyqzJ3o/0lkk/N97gfVRLW5hA29yeAwaCViZNCP8iC9aO0q9fQojoa7NQnAtw==' }],
};

export class LgDriver {
  constructor(device, store, { wsUrl } = {}) {
    this.device = device;
    this.store = store;
    this.wsUrl = wsUrl || `wss://${device.ip}:3001`;
    this.ws = null;
    this.pointer = null;
    this.seq = 0;
    this.pending = new Map();
  }
  get capabilities() {
    return { keys: [...Object.keys(SSAP), ...Object.keys(BUTTONS).filter((k) => BUTTONS[k]), 'MUTE', 'PLAY_PAUSE', 'POWER_ON', 'INPUT'], apps: true, text: true, powerOn: true };
  }
  async open() {
    if (this.ws?.readyState === WebSocket.OPEN) return;
    const ws = new WebSocket(this.wsUrl, { rejectUnauthorized: false, handshakeTimeout: 5000 });
    ws.on('message', (raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      const p = this.pending.get(m.id);
      if (!p) return;
      if (m.type === 'registered') { this.store.set('clientKey', m.payload['client-key']); p.resolve(m.payload); this.pending.delete(m.id); }
      else if (m.type === 'error') { p.reject(new Error(m.error || 'TV error')); this.pending.delete(m.id); }
      else if (m.type === 'response' && !(m.payload?.pairingType)) { p.resolve(m.payload); this.pending.delete(m.id); }
    });
    ws.on('close', () => { if (this.ws === ws) { this.ws = null; this.pointer?.close(); this.pointer = null; } });
    await new Promise((res, rej) => { ws.once('open', res); ws.once('error', rej); });
    this.ws = ws;
    const clientKey = this.store.get('clientKey');
    // 60s: the first time, someone has to accept the prompt on the TV.
    await this.call('register', null, { pairingType: 'PROMPT', manifest: MANIFEST, ...(clientKey ? { 'client-key': clientKey } : {}) }, 60000)
      .catch((e) => { ws.close(); throw new Error(`Pairing failed: ${e.message}. Accept the prompt on the TV and retry.`); });
  }
  call(type, uri, payload, ms = 6000) {
    const id = `r${++this.seq}`;
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { this.pending.delete(id); reject(new Error('TV did not answer')); }, ms);
      this.pending.set(id, { resolve: (v) => { clearTimeout(t); resolve(v); }, reject: (e) => { clearTimeout(t); reject(e); } });
      this.ws.send(JSON.stringify({ id, type, ...(uri ? { uri } : {}), ...(payload ? { payload } : {}) }));
    });
  }
  async request(uri, payload) { await this.open(); return this.call('request', uri, payload); }
  async button(name) {
    await this.open();
    if (!this.pointer || this.pointer.readyState !== WebSocket.OPEN) {
      const { socketPath } = await this.call('request', 'ssap://com.webos.service.networkinput/getPointerInputSocket');
      const p = new WebSocket(socketPath, { rejectUnauthorized: false });
      await new Promise((res, rej) => { p.once('open', res); p.once('error', rej); });
      this.pointer = p;
    }
    this.pointer.send(`type:button\nname:${name}\n\n`);
  }
  async connect() { await this.open(); return this.info(); }
  async info() {
    try {
      const s = await this.request('ssap://system/getSystemInfo');
      return { model: s.modelName, name: this.device.name };
    } catch { return {}; }
  }
  async key(k) {
    if (k === 'MUTE') {
      const st = await this.request('ssap://audio/getStatus').catch(() => ({}));
      return this.request('ssap://audio/setMute', { mute: !st.mute });
    }
    if (k === 'PLAY_PAUSE') { this.playing = !this.playing; return this.request(this.playing ? SSAP.PLAY : SSAP.PAUSE); }
    if (k === 'INPUT') return this.request('ssap://system.launcher/launch', { id: 'com.webos.app.inputpicker' }).catch(() => this.button('INPUT'));
    if (SSAP[k]) return this.request(SSAP[k]);
    if (BUTTONS[k]) return this.button(BUTTONS[k]);
    throw new Error(`LG has no ${k} button`);
  }
  async apps() {
    const r = await this.request('ssap://com.webos.applicationManager/listApps');
    return (r.apps || []).filter((a) => a.visible !== false).map((a) => ({ id: a.id, name: a.title, icon: a.icon || null }));
  }
  async launch(id) { await this.request('ssap://system.launcher/launch', { id }); }
  async text(s) { await this.request('ssap://com.webos.service.ime/insertText', { text: s, replace: 0 }); }
  close() { this.pointer?.close(); this.ws?.close(); }
}
