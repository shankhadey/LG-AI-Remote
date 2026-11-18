import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createBridge } from '../server.js';

test('bridge: discover → select → key → apps, config persisted', async () => {
  const keys = [];
  const roku = http.createServer((req, res) => {
    keys.push(req.url);
    if (req.url === '/query/apps') return res.end('<apps><app id="12">Netflix</app></apps>');
    if (req.url === '/query/device-info') return res.end('<device-info><model-name>X</model-name><wifi-mac>aa:bb:cc:dd:ee:ff</wifi-mac></device-info>');
    res.end();
  });
  await new Promise((r) => roku.listen(0, '127.0.0.1', r));
  const cfgFile = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'tvr-')), 'config.json');
  const woke = [];
  const bridge = createBridge({
    configFile: cfgFile,
    driverOptions: { roku: { base: `http://127.0.0.1:${roku.address().port}` } },
    discoverFn: async () => [
      { id: '10.0.0.5', ip: '10.0.0.5', brand: 'roku', name: 'Living Room', services: [] },
      { id: '10.0.0.6', ip: '10.0.0.6', brand: 'androidtv', name: 'Chromecast', services: [] },
    ],
    wakeFn: async (mac) => woke.push(mac),
  });
  const srv = http.createServer(bridge.handle);
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${srv.address().port}`;
  const call = async (p, body) => {
    const r = await fetch(base + p, { method: body ? 'POST' : 'GET', headers: { 'Content-Type': 'application/json' }, body: body && JSON.stringify(body) });
    return { status: r.status, data: await r.json() };
  };

  assert.equal((await call('/api/state')).data.tv, null);
  const scan = (await call('/api/discover', {})).data;
  assert.equal(scan[0].supported, true);
  assert.equal(scan[1].supported, false);
  assert.match(scan[1].note, /Google TV/);
  assert.equal((await call('/api/select', { id: '10.0.0.6' })).status, 400);
  const sel = await call('/api/select', { id: '10.0.0.5' });
  assert.equal(sel.data.tv.name, 'Living Room');
  assert.ok(sel.data.capabilities.keys.includes('HOME'));
  const paired = await call('/api/pair', {});
  assert.equal(paired.data.tv.mac, 'aa:bb:cc:dd:ee:ff');
  assert.equal((await call('/api/key', { key: 'HOME' })).status, 200);
  assert.ok(keys.includes('/keypress/Home'));
  assert.equal((await call('/api/apps')).data[0].name, 'Netflix');
  assert.equal(JSON.parse(fs.readFileSync(cfgFile, 'utf8')).tv.ip, '10.0.0.5');
  assert.equal((await call('/api/nope')).status, 404);

  const page = await fetch(base + '/');
  assert.match(await page.text(), /TV Remote/);
  assert.equal((await fetch(base + '/../server.js')).status, 404);
  srv.close(); roku.close();
});
