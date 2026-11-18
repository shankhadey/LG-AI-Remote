// SSDP (UPnP) discovery: finds TVs and media devices on the local network.
import dgram from 'node:dgram';

const ADDR = '239.255.255.250';
const PORT = 1900;

// Search targets that smart TVs answer to. ssdp:all catches the rest.
const TARGETS = [
  'ssdp:all',
  'roku:ecp',
  'urn:lge-com:service:webos-second-screen:1',
  'urn:samsung.com:device:RemoteControlReceiver:1',
  'urn:schemas-sony-com:service:ScalarWebAPI:1',
  'urn:dial-multiscreen-org:service:dial:1',
  'urn:schemas-upnp-org:device:MediaRenderer:1',
];

export function parseHeaders(msg) {
  const out = {};
  for (const line of msg.split(/\r?\n/).slice(1)) {
    const i = line.indexOf(':');
    if (i > 0) out[line.slice(0, i).trim().toLowerCase()] = line.slice(i + 1).trim();
  }
  return out;
}

/** Sends M-SEARCH and returns raw responses grouped by IP: { ip: [headers...] } */
export function search(timeoutMs = 3000) {
  return new Promise((resolve) => {
    const byIp = {};
    const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    sock.on('message', (buf, rinfo) => {
      const h = parseHeaders(buf.toString());
      (byIp[rinfo.address] ||= []).push(h);
    });
    sock.on('error', () => {});
    sock.bind(0, () => {
      for (const st of TARGETS) {
        const msg = Buffer.from(
          `M-SEARCH * HTTP/1.1\r\nHOST: ${ADDR}:${PORT}\r\nMAN: "ssdp:discover"\r\nMX: 2\r\nST: ${st}\r\n\r\n`);
        // Send twice: UDP is lossy.
        sock.send(msg, PORT, ADDR);
        setTimeout(() => sock.send(msg, PORT, ADDR), 400);
      }
    });
    setTimeout(() => { try { sock.close(); } catch {} resolve(byIp); }, timeoutMs);
  });
}

function tag(xml, name) {
  const m = xml.match(new RegExp(`<(?:\\w+:)?${name}>([^<]*)</(?:\\w+:)?${name}>`, 'i'));
  return m ? m[1].trim() : '';
}

async function fetchDescription(url) {
  try {
    const r = await fetch(url, { signal: AbortSignal.timeout(2500) });
    const xml = await r.text();
    return {
      friendlyName: tag(xml, 'friendlyName'),
      manufacturer: tag(xml, 'manufacturer'),
      modelName: tag(xml, 'modelName'),
      modelNumber: tag(xml, 'modelNumber'),
      raw: xml,
    };
  } catch { return null; }
}

/** Decide which driver can control a device, from its SSDP answers + description. */
export function classify(headersList, desc) {
  const sts = headersList.map((h) => `${h.st || h.nt || ''} ${h.server || ''} ${h.usn || ''}`).join(' ').toLowerCase();
  const maker = `${desc?.manufacturer || ''} ${desc?.raw?.slice(0, 4000) || ''}`.toLowerCase();
  if (sts.includes('roku:ecp') || maker.includes('roku')) return 'roku';
  if (sts.includes('webos-second-screen') || sts.includes('webos') || maker.includes('lg electronics')) return 'lg';
  if (sts.includes('samsung') || maker.includes('samsung')) return 'samsung';
  if (sts.includes('schemas-sony-com') || maker.includes('sony')) return 'sony';
  if (maker.includes('google') || sts.includes('android')) return 'androidtv';
  return null;
}

/** Full discovery: returns a list of candidate devices, TVs first. */
export async function discover(timeoutMs = 3000) {
  const byIp = await search(timeoutMs);
  const devices = await Promise.all(Object.entries(byIp).map(async ([ip, list]) => {
    const location = list.find((h) => h.location)?.location;
    const desc = location ? await fetchDescription(location) : null;
    const brand = classify(list, desc);
    return {
      id: ip,
      ip,
      brand,
      name: desc?.friendlyName || ip,
      manufacturer: desc?.manufacturer || '',
      model: [desc?.modelName, desc?.modelNumber].filter(Boolean).join(' '),
      services: [...new Set(list.map((h) => h.st || h.nt).filter(Boolean))],
      location,
    };
  }));
  return devices.sort((a, b) => (b.brand ? 1 : 0) - (a.brand ? 1 : 0));
}
