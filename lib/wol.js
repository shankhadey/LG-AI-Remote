// Wake-on-LAN: turns on TVs whose network stays alive in standby (LG, Samsung, Sony).
import dgram from 'node:dgram';

export function wake(mac, { address = '255.255.255.255', port = 9 } = {}) {
  const hex = String(mac || '').replace(/[^0-9a-f]/gi, '');
  if (hex.length !== 12) return Promise.reject(new Error('Wake-on-LAN needs the TV\'s MAC address (set it in Settings)'));
  const m = Buffer.from(hex, 'hex');
  const packet = Buffer.concat([Buffer.alloc(6, 0xff), ...Array(16).fill(m)]);
  return new Promise((resolve, reject) => {
    const s = dgram.createSocket('udp4');
    s.bind(() => {
      s.setBroadcast(true);
      let sent = 0;
      for (let i = 0; i < 3; i++) s.send(packet, port, address, (e) => {
        if (e) { s.close(); return reject(e); }
        if (++sent === 3) { s.close(); resolve(); }
      });
    });
  });
}
