# Backlog

Things we decided to remember for later. Newest first.

## Power / start-up
- **TV starts on "No signal" after waking.** LG starts on Live TV (or the last input); there's no antenna/cable,
  so it shows "No signal" until Home is pressed. Options: the TV's *Home Auto Launch* / start-up screen setting
  (Settings › General › Additional Settings › Home Settings; location varies by firmware), or have the app press
  Home after waking when the foreground app is Live TV. Not an app bug.
- **Check wake after a long time off** with Quick Start+ OFF (e.g. next morning). Wake-on-LAN worked right after
  switching off; some TVs drop Wi-Fi in deep standby. If it fails, turn Quick Start+ back on.
- **Quick Start+:** OFF clears stuck apps on every off/on (the Netflix hang was fixed by a real restart) and
  app power-on still worked. Keep OFF if the long-off wake check passes.

## Diagnostics
- Log the TV's foreground-app subscription events, and attempts still in flight when Diagnostics is opened.

## AI: "what should we watch?" (design stage)
- Open questions to the user: who watches, which services, region (US?), movies/series, "continue watching"
  priority, OK with TMDB + Anthropic API keys.
