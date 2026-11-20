# Backlog

Things we decided to remember for later.

## To test on the TV (v0.4.0)
- **Turn-off timer (15 min):** pop-ups at 10, 5 and 1 min; TV turns off at zero; +10 min updates.
- **Tap-to-pause** in Netflix, Prime, Disney+, YouTube (after setting taps back to *A · OK*), then send
  Diagnostics to see whether each app reports "playing"/"paused".
- **Wake after a long time off** with Quick Start+ OFF (e.g. next morning). Worked right after switching off;
  some TVs drop Wi-Fi in deep standby. If it fails, turn Quick Start+ back on.

## Small fixes (next update)
- **Re-subscribe when a TV subscription fails.** Diagnostics showed `SUB ERR 500 Application error`; after that,
  on-screen app / play state stop updating until reconnect.
- **Treat the TV's web browser like Home**, not as "watching" (taps should select there, not pause/play).
- **Tap method stuck on "Pointer click"** from the v0.2.7 test; the TV ignores it. User fix: TV name ›
  Fix touchpad tap › A. Consider resetting to OK once in an update.
- Diagnostics: also show attempts still in flight when Diagnostics is opened.

## Power / start-up
- **TV starts on "No signal" after waking.** LG starts on Live TV (or the last input); there's no antenna/cable,
  so it shows "No signal" until Home is pressed. Options: the TV's *Home Auto Launch* / start-up screen setting
  (Settings › General › Additional Settings › Home Settings; location varies by firmware), or have the app press
  Home after waking when the foreground app is Live TV. Not an app bug.
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
- Tap-to-pause and removing the Play button (v0.4.0).
- One-tap timers: turn-off with reminders, full-screen countdown (v0.4.0).
- Log subscription events in Diagnostics (v0.4.0).
