import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { WebSocketServer } from 'ws';
import { RokuDriver } from '../lib/drivers/roku.js';
import { SamsungDriver } from '../lib/drivers/samsung.js';
import { LgDriver } from '../lib/drivers/lg.js';
import { SonyDriver } from '../lib/drivers/sony.js';
import { classify, parseHeaders } from '../lib/ssdp.js';

const memStore = (init = {}) => { const s = { ...init }; return { get: (k) => s[k], set: (k, v) => { s[k] = v; }, s }; };
const tick = () => new Promise((r) => setTimeout(r, 50));
const listen = (srv) => new Promise((r) => srv.listen(0, '127.0.0.1', () => r(srv.address().port)));

test('ssdp: parse + classify brands', () => {
  const h = parseHeaders('HTTP/1.1 200 OK\r\nST: roku:ecp\r\nLOCATION: http://1.2.3.4:8060/\r\n\r\n');
  assert.equal(h.st, 'roku:ecp');
  assert.equal(h.location, 'http://1.2.3.4:8060/');
  assert.equal(classify([h], null), 'roku');
  assert.equal(classify([{ st: 'urn:lge-com:service:webos-second-screen:1' }], null), 'lg');
  assert.equal(classify([{ st: 'urn:samsung.com:device:RemoteControlReceiver:1' }], null), 'samsung');
  assert.equal(classify([{ st: 'upnp:rootdevice' }], { manufacturer: 'Sony Corporation' }), 'sony');
  assert.equal(classify([{ st: 'upnp:rootdevice' }], { manufacturer: 'Netgear' }), null);
});

test('roku: keys, apps, launch, text', async () => {
  const hits = [];
  const srv = http.createServer((req, res) => {
    hits.push(`${req.method} ${req.url}`);
    if (req.url === '/query/apps') return res.end('<apps><app id="12" type="appl" version="1">Netflix</app><app id="837">YouTube &amp; Co</app></apps>');
    if (req.url === '/query/device-info') return res.end('<device-info><vendor-name>TCL</vendor-name><model-name>55S</model-name><user-device-name>Den</user-device-name><power-mode>PowerOn</power-mode></device-info>');
    res.end();
  });
  const port = await listen(srv);
  const d = new RokuDriver({ ip: '127.0.0.1' }, memStore(), { base: `http://127.0.0.1:${port}` });
  await d.key('VOL_UP'); await d.key('OK'); await d.key('NUM_7'); await d.launch('12'); await d.text('hi');
  assert.deepEqual(await d.apps(), [{ id: '12', name: 'Netflix', icon: '/api/roku-icon/12' }, { id: '837', name: 'YouTube & Co', icon: '/api/roku-icon/837' }]);
  assert.equal((await d.info()).name, 'Den');
  assert.deepEqual(hits.filter((h) => h.startsWith('POST')), ['POST /keypress/VolumeUp', 'POST /keypress/Select', 'POST /keypress/Lit_7', 'POST /launch/12', 'POST /keypress/Lit_h', 'POST /keypress/Lit_i']);
  srv.close();
});

test('samsung: pairing token saved, keys + apps over websocket', async () => {
  const wss = new WebSocketServer({ port: 0 });
  const got = [];
  let seenUrl;
  wss.on('connection', (ws, req) => {
    seenUrl = req.url;
    ws.send(JSON.stringify({ event: 'ms.channel.connect', data: { token: '777' } }));
    ws.on('message', (raw) => {
      const m = JSON.parse(raw);
      got.push(m);
      if (m.params?.event === 'ed.installedApp.get') ws.send(JSON.stringify({ event: 'ed.installedApp.get', data: { data: [{ appId: '3201907018807', name: 'Netflix', app_type: 2 }] } }));
    });
  });
  await new Promise((r) => wss.on('listening', r));
  const store = memStore();
  const d = new SamsungDriver({ ip: '127.0.0.1' }, store, { wsBase: `ws://127.0.0.1:${wss.address().port}`, httpBase: 'http://127.0.0.1:1' });
  await d.key('VOL_UP');
  await tick();
  assert.equal(store.s.token, '777');
  assert.match(seenUrl, /name=/);
  assert.equal(got[0].params.DataOfCmd, 'KEY_VOLUP');
  const apps = await d.apps();
  assert.equal(apps[0].name, 'Netflix');
  await d.launch('3201907018807');
  await tick();
  assert.equal(got.at(-1).params.data.action_type, 'DEEP_LINK');
  await d.key('PLAY_PAUSE'); await d.key('PLAY_PAUSE');
  await tick();
  assert.deepEqual(got.slice(-2).map((m) => m.params.DataOfCmd), ['KEY_PLAY', 'KEY_PAUSE']);
  d.close(); wss.close();
});

test('lg: register prompt, ssap requests, pointer buttons', async () => {
  const pointerMsgs = [];
  const pointer = new WebSocketServer({ port: 0 });
  pointer.on('connection', (ws) => ws.on('message', (m) => pointerMsgs.push(String(m))));
  await new Promise((r) => pointer.on('listening', r));
  const reqs = [];
  const wss = new WebSocketServer({ port: 0 });
  wss.on('connection', (ws) => ws.on('message', (raw) => {
    const m = JSON.parse(raw);
    reqs.push(m);
    if (m.type === 'register') {
      ws.send(JSON.stringify({ id: m.id, type: 'response', payload: { pairingType: 'PROMPT', returnValue: true } }));
      ws.send(JSON.stringify({ id: m.id, type: 'registered', payload: { 'client-key': 'abc' } }));
    } else if (m.uri === 'ssap://com.webos.service.networkinput/getPointerInputSocket') {
      ws.send(JSON.stringify({ id: m.id, type: 'response', payload: { socketPath: `ws://127.0.0.1:${pointer.address().port}/p` } }));
    } else if (m.uri === 'ssap://com.webos.applicationManager/listApps') {
      ws.send(JSON.stringify({ id: m.id, type: 'response', payload: { apps: [{ id: 'netflix', title: 'Netflix', icon: 'http://x/n.png' }, { id: 'hidden', title: 'X', visible: false }] } }));
    } else if (m.uri === 'ssap://audio/getStatus') {
      ws.send(JSON.stringify({ id: m.id, type: 'response', payload: { mute: false } }));
    } else {
      ws.send(JSON.stringify({ id: m.id, type: 'response', payload: { returnValue: true } }));
    }
  }));
  await new Promise((r) => wss.on('listening', r));
  const store = memStore();
  const d = new LgDriver({ ip: '127.0.0.1' }, store, { wsUrl: `ws://127.0.0.1:${wss.address().port}` });
  await d.key('VOL_UP');
  assert.equal(store.s.clientKey, 'abc');
  assert.equal(reqs[0].payload.manifest.signed.appId, 'com.lge.test');
  assert.equal(reqs[1].uri, 'ssap://audio/volumeUp');
  await d.key('MUTE');
  assert.deepEqual(reqs.at(-1).payload, { mute: true });
  await d.key('OK'); await d.key('LEFT');
  await new Promise((r) => setTimeout(r, 50));
  assert.deepEqual(pointerMsgs, ['type:button\nname:ENTER\n\n', 'type:button\nname:LEFT\n\n']);
  assert.deepEqual(await d.apps(), [{ id: 'netflix', name: 'Netflix', icon: 'http://x/n.png' }]);
  d.close(); wss.close(); pointer.close();
});

test('sony: PSK header, IRCC codes, power', async () => {
  const seen = [];
  let power = 'standby';
  const srv = http.createServer((req, res) => {
    let b = ''; req.on('data', (c) => { b += c; }); req.on('end', () => {
      seen.push({ url: req.url, psk: req.headers['x-auth-psk'], body: b });
      if (req.url === '/sony/IRCC') return res.end('<ok/>');
      const m = JSON.parse(b);
      const result = {
        getRemoteControllerInfo: [{}, [{ name: 'VolumeUp', value: 'AAAAAQAAAAEAAAASAw==' }, { name: 'Confirm', value: 'AAAAAQAAAAEAAABlAw==' }]],
        getPowerStatus: [{ status: power }],
        setPowerStatus: [],
      }[m.method] ?? [];
      if (m.method === 'setPowerStatus') power = m.params[0].status ? 'active' : 'standby';
      res.end(JSON.stringify({ id: 1, result }));
    });
  });
  const port = await listen(srv);
  const d = new SonyDriver({ ip: '127.0.0.1' }, memStore({ psk: '1234' }), { base: `http://127.0.0.1:${port}` });
  await d.key('VOL_UP');
  const ircc = seen.find((s) => s.url === '/sony/IRCC');
  assert.match(ircc.body, /AAAAAQAAAAEAAAASAw==/);
  assert.equal(ircc.psk, '1234');
  await d.key('POWER');
  assert.equal(power, 'active');
  await assert.rejects(d.key('NUM_5'), /no NUM_5/);
  srv.close();
});
