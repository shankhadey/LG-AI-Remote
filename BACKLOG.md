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

## AI: "what should we watch?" (designed, awaiting go-ahead to build)
- Decisions: mix of together/separate (profiles + Together); US; services Netflix, Prime Video, Disney+,
  Apple TV+, Max, Hulu, hoichoi, ZEE5; movies + series; "keep watching" first; names entered by the user in
  first-run setup (nothing household-specific built in).
- Data: TMDB (catalog + US where-to-watch). Model: Groq free tier, key entered in settings; provider must be
  swappable (Gemini / paid / on-device / our own server later).
- The model only chooses from TMDB candidate IDs (ranking/classification + a one-line reason), so it can't
  invent titles or availability. hoichoi / ZEE5 coverage in TMDB US may be thin: check when building.
- Screens (v2 design): entry card · Tonight (day-aware time left, who, language Any/English/Bengali/Hindi,
  keep watching, moods ordered by time/day, say it, Surprise us, "Show on TV" checkbox) · 3 picks (service,
  language, length, ends at, rating, why incl. "fans of X also loved it" from TMDB; Play / Not now / Never) ·
  setup (names, services, languages, Sun–Thu and Fri–Sat bedtimes) · TV view of the picks via the TV browser.
- Not now = skip this session (logged with time/day). Never = never for whoever is watching (Together is its
  own profile). No "shows you loved" step in setup; maybe an optional "tell me a few shows you loved" card later.
- Context: learn per time slot (who usually watches, which moods/languages), store picks with time/day.
- Measure: seconds from opening to Play; quick-back if the app is left within 30 min (<15 strong, 15–30 weak),
  also used as a soft negative signal. Picks summary + last 20 sessions in Diagnostics with Copy / Share.
  Limitation: can't see title changes inside an app, only which app is in front.

## Before selling (if it goes to the stores)
- TMDB commercial agreement; JustWatch agreement for where-to-watch data used commercially.
- Our own small server holding a paid model key (no keys inside a sold app; free tiers aren't for products).
- Real release signing key (switch before handing the app to others; everyone reinstalls once).
- Play Store: privacy policy, data-safety form, mic + foreground-service (timer) justifications, local-network
  permission on newer Android.
- No LG / Netflix / other trademarks in the app name, icon or listing; keep letter tiles, not real logos.
- Platform risk: LG's control protocol is unofficial and LG has already changed it once; plan for breakage.
- Market: Samsung and iPhone versions.

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
