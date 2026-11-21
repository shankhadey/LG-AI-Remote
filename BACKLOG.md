# Backlog

Things we decided to remember for later.

## To test on the TV
- **v0.5.0 "What should we watch?"** — first run: enter names, services, languages, bedtimes, TMDB key (and Groq
  key for AI). Check: picks are on your services; hoichoi / ZEE5 coverage; "Not now" / "Never" swap a pick;
  Play opens the right app + "Look for …" pop-up on the TV; Max / hoichoi / ZEE5 need opening once from the TV so
  the app learns their IDs. Show on TV: phone becomes a touchpad; choose and pick on the TV. Diagnostics
  › Share includes the Picks report.
- **v0.4.1: after waking the TV from the app, it should open Home instead of "No signal".** Setting under the
  TV name menu ("After turning on: open Home").
- **Wake after a long time off** with Quick Start+ OFF (e.g. next morning). Worked right after switching off;
  some TVs drop Wi-Fi in deep standby. If it fails, turn Quick Start+ back on.

## Parked (low impact)
- Diagnostics: also show attempts still in flight when Diagnostics is opened.

## Power / start-up
- **Quick Start+:** OFF clears stuck apps on every off/on (the Netflix hang was fixed by a real restart) and
  app power-on still worked. Keep OFF if the long-off wake check passes.

## AI: "what should we watch?" (built in v0.5.0, testing)
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
  Option: on-phone AI first on supported phones (no server cost, private), server only for other phones.
- Real release signing key (switch before handing the app to others; everyone reinstalls once).
- Play Store: privacy policy, data-safety form, mic + foreground-service (timer) justifications, local-network
  permission on newer Android.
- No LG / Netflix / other trademarks in the app name, icon or listing; keep letter tiles, not real logos.
- Platform risk: LG's control protocol is unofficial and LG has already changed it once; plan for breakage.
- Market: Samsung and iPhone versions.

## Known limits of v0.5.x
- On-phone AI uses a beta Google API; Android downloads the model in the background the first time (the first
  evening may use Groq/rules). It's smaller than Groq's model: reasons may be plainer.
- Apps can't be opened at a specific title from outside; the app opens the service and shows "Look for: <title>".
- The phone must stay nearby with the app open while the TV page is up (no background service for it yet).
- Groq model name is fixed in settings storage (default llama-3.3-70b-versatile); if Groq retires it, picks fall
  back to rules and Diagnostics shows the error. Add a model field if that happens.
- Keys are stored in plain app storage on the phone (fine for personal use; a sold app would use our server).

## Later
- **Samsung support.** Step 1: refactor into a per-brand TV driver with LG unchanged (all tests must pass, plus a
  few days of real use). Step 2: Samsung driver (pairing, keys, app launch with Samsung IDs, installed-app list,
  typing, power). Samsung gaps: no reliable on-screen app/play state, no TV pop-ups, no pointer, so the touchpad
  becomes swipe + tap-OK on Samsung only. Needs a real Samsung TV to test. After the AI feature.
- **iPhone version** for the other household phone (original ask).

## Done
- v0.6.2: "Pointing at" now comes from the pointer's movement. The TV browser doesn't give the page focus until
  its first click, so focus-based reporting stayed silent after (re)opening the page. The label also clears when
  the TV screen changes under a still pointer.
- v0.6.1: picker on the TV works with the touchpad (confirmed: who → mood → Play opened Disney+). Cause: v0.6.0 on the TV — page loaded and the pointer moved, but the page never reported a
  button under the pointer and OK (ENTER) clicked nothing. Now: tap sends a real pointer click while the picks
  page is up; the page clicks the button under the pointer on OK; Diagnostics records PAGE lines (loaded, pointer
  seen, keys seen, clicks, script errors). Page verified in desktop Chromium. Wake gap confirmed TV-side (attempts
  time out until ~23–25 s after wake).
- v0.6.0: "On TV" ticked = the phone becomes a full-screen touchpad for the picker on the TV (point and click,
  "Pointing at: …" reported by the TV page, mic at the bottom). Unticked = the normal phone screens. The chooser
  is always on the TV page now, so the separate lean-back setting is gone.
- v0.5.5: Diagnostics logs each failed connect attempt during turn-on (to see whether the ~9 s between "network up"
  and "connected" is the TV booting or the app). Closing the picker before any picks is "closed before any
  picks", not "gave up". v0.5.4 turn-on confirmed working on the TV (network up 17.3 s, connected 26.1 s).
- v0.5.4: turning off now closes the connection (the TV vanished without closing it, so a turn-on within ~20 s
  "connected" instantly on the dead socket and then dropped). The wake loop starts from a clean connection and a
  failed power check means "retry", not "on". Late events from an old socket can no longer kill a new one.
- v0.5.3: turn-on fix. The wake loop now creates the TV connection itself (it used to hand off to a slow single
  connect when the app was opened with the TV off, adding ~11 s and briefly showing the Turn-on screen again).
  Stays on "Turning on…" until connected, then goes straight to the remote. WakeLoop tested on a virtual clock.
  Quick-backs: measured from when the app actually came up; leaving within 2 min is a "no-start" (not a bail,
  not liked, excluded from the percentages, shown in Diagnostics).
- v0.5.2: faster turn-on detection (wake sent immediately, both ports checked at once every 0.5 s, short
  connection retries; timeline in Diagnostics as WAKE lines). Turning-on screen: one state, ring around the
  button, "Still waking the TV · N s", status line says Turning on. TV name falls back to the model name.
  Setup: "Who makes the picks?" — This phone's AI / Groq / No AI (auto-fallback), labelled key fields.
- v0.5.1: on-phone AI (Gemini Nano via Android's on-device AI, ML Kit GenAI Prompt API beta4) as a fallback:
  Groq → on-phone → rules, or on-phone first with "Prefer on-phone AI". Shared prompts + "only candidate IDs"
  check for every engine. Diagnostics shows the AI order, on-phone status and which engine made each set of picks.
  Toolchain: Kotlin 2.0.21 → 2.3.21 (needed by the library).
- v0.5.0: "What should we watch?" — phone picker, TMDB + Groq (rules fallback), learning (Not now per time slot,
  Never per person, plays, quick-backs), time/day context, languages, Show on TV with lean-back mode (setup
  toggle), voice ("what should we watch?", "find us something funny"), app-icon shortcut, Diagnostics Picks report
  with Share. The TV browser now counts as Home (taps select there).
- Re-subscribe automatically when the TV ends a live update (v0.4.1).
- Open Home after the app wakes the TV onto Live TV / "No signal" (v0.4.1, setting).
- Turn-off timer and tap-to-pause tested on the TV; tap method reset to OK by the user.
- Tap-to-pause and removing the Play button (v0.4.0).
- One-tap timers: turn-off with reminders, full-screen countdown (v0.4.0).
- Log subscription events in Diagnostics (v0.4.0).
