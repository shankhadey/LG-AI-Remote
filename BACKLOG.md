# Backlog

Things we decided to remember for later.

## To test on the TV
- **v0.4.1: after waking the TV from the app, it should open Home instead of "No signal".** Setting under the
  TV name menu ("After turning on: open Home").
- **Wake after a long time off** with Quick Start+ OFF (e.g. next morning). Worked right after switching off;
  some TVs drop Wi-Fi in deep standby. If it fails, turn Quick Start+ back on.

## Parked (low impact)
- **Treat the TV's web browser like Home**, not as "watching" (taps should select there, not pause/play).
- Diagnostics: also show attempts still in flight when Diagnostics is opened.

## Power / start-up
- **Quick Start+:** OFF clears stuck apps on every off/on (the Netflix hang was fixed by a real restart) and
  app power-on still worked. Keep OFF if the long-off wake check passes.

## AI: "what should we watch?" (design stage, next big feature)
- Open questions to the user: who watches, which services, region (US?), movies/series, "continue watching"
  priority, OK with TMDB + Anthropic API keys. Then draw the screens before building.

## Later
- **Samsung support.** Step 1: refactor into a per-brand TV driver with LG unchanged (all tests must pass, plus a
  few days of real use). Step 2: Samsung driver (pairing, keys, app launch with Samsung IDs, installed-app list,
  typing, power). Samsung gaps: no reliable on-screen app/play state, no TV pop-ups, no pointer, so the touchpad
  becomes swipe + tap-OK on Samsung only. Needs a real Samsung TV to test. After the AI feature.
- **iPhone version** for the other household phone (original ask).

## Done
- Re-subscribe automatically when the TV ends a live update (v0.4.1).
- Open Home after the app wakes the TV onto Live TV / "No signal" (v0.4.1, setting).
- Turn-off timer and tap-to-pause tested on the TV; tap method reset to OK by the user.
- Tap-to-pause and removing the Play button (v0.4.0).
- One-tap timers: turn-off with reminders, full-screen countdown (v0.4.0).
- Log subscription events in Diagnostics (v0.4.0).
