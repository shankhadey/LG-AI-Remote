// "What's on my network and what can it do?" Run: npm run probe
import { discover } from '../lib/ssdp.js';
import { DRIVERS } from '../server.js';

const devices = await discover(4000);
if (!devices.length) { console.log('No devices answered. Is this machine on the same Wi-Fi as the TV?'); process.exit(1); }
for (const d of devices) {
  console.log(`\n${d.brand ? '📺' : '  '} ${d.name}  (${d.ip})`);
  console.log(`   ${[d.manufacturer, d.model].filter(Boolean).join(' · ') || 'unknown device'}`);
  console.log(`   driver: ${d.brand ? (DRIVERS[d.brand] ? d.brand : `${d.brand} (not supported yet)`) : 'none'}`);
  for (const s of d.services) console.log(`   - ${s}`);
  const D = DRIVERS[d.brand];
  if (!D) continue;
  const drv = new D(d, { get: () => undefined, set: () => {} });
  console.log(`   buttons: ${drv.capabilities.keys.join(' ')}`);
  // Read-only queries that need no pairing.
  if (d.brand === 'roku' || d.brand === 'samsung') {
    try { console.log('   info:', await drv.info()); } catch (e) { console.log('   info: unavailable -', e.message); }
  }
  if (d.brand === 'roku') {
    try { console.log(`   apps: ${(await drv.apps()).map((a) => a.name).join(', ')}`); } catch {}
  }
}
