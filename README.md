# TV Remote

Control the TV from your phones instead of the physical remote. It has two parts:

- **Bridge** (`server.js`): a small Node program that runs on any always-on computer at home (Mac, PC, Raspberry Pi, NAS). It finds the TV on the network, pairs with it, and passes on button presses.
- **Phone app** (`public/`): a web app the bridge serves. Each phone opens it once and adds it to the home screen, after which it works like an app. Nothing goes through an app store.

You need the bridge because phone browsers aren't allowed to talk to most TVs directly: Samsung and LG use self-signed secure connections that browsers block.

## Supported TVs

| TV | How it connects | First-time pairing |
|---|---|---|
| Samsung (Tizen, 2016+) | WebSocket, port 8002 | Press **Allow** on the TV |
| LG (webOS) | SSAP WebSocket, port 3001 | Accept the prompt on the TV |
| Roku / Roku TV (TCL, Hisense, …) | ECP HTTP, port 8060 | None. Set *Control by mobile apps* to Enabled |
| Sony Bravia | REST + IRCC | Set a Pre-Shared Key under *IP control* on the TV |
| Android / Google TV | Not supported yet | |

Features: power (Wake-on-LAN to turn the TV on), volume and mute, channels, d-pad with OK/Back/Home/Menu, play/pause/rewind/forward, number pad, an app launcher showing the TV's installed apps, typing into TV search boxes, and hold-to-repeat on volume and arrows. Buttons the TV can't handle are hidden.

## Setup (about 5 minutes)

On the computer that will run the bridge (it needs [Node.js](https://nodejs.org) 18 or newer):

```bash
git clone <this repo> && cd tv-remote
npm install
npm run probe     # optional: lists TVs/devices on the network and what each can do
npm start
```

The bridge prints an address like `http://192.168.1.20:8080`. Then:

1. Open that address on your phone (on home Wi-Fi), tap **Search the network**, pick the TV, and tap **Connect**. Accept the prompt on the TV if one appears.
2. **Add to home screen:** in Safari on iPhone, tap Share › *Add to Home Screen*. In Chrome on Android, tap ⋮ › *Add to Home screen* / *Install app*.
3. On your wife's phone, just open the same address and add it to the home screen. The TV is already paired; the pairing lives on the bridge.

**Turning the TV on** from the phone needs the TV's network to stay awake in standby. Enable *Settings › General › Network › Expert Settings › Power On with Mobile* on Samsung, *Turn on via Wi-Fi* / *Quick Start+* on LG, or *Fast TV start* on Roku. The TV's MAC address is filled in automatically after pairing where the TV reports it; otherwise enter it under ⚙ Settings.

### Keep the bridge running

- **Raspberry Pi / Linux:** `sudo npm i -g pm2 && pm2 start server.js --name tv-remote && pm2 save && pm2 startup`
- **Mac/PC:** the same `pm2` commands work, or simply leave `npm start` running.
- Give the bridge machine a fixed IP in your router (a DHCP reservation) so the phone bookmark keeps working.

### Optional

- `REMOTE_PIN=1234 npm start` requires a PIN on each phone (enter it under ⚙ Settings).
- `PORT=9000 npm start` changes the port.
- To control the TV from outside the house, put the bridge and phones on [Tailscale](https://tailscale.com). Don't open the port on your router.

## Development

```bash
npm test   # drivers are tested against simulated Roku/Samsung/LG/Sony TVs
```

Pairing tokens are kept in `config.json` next to `server.js` (git-ignored).
