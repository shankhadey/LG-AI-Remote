# Backlog

## LAUNCH PLAN (started 22 Nov) — Android first, in market; no half-done ideas
Critical path: Google requires new personal developer accounts to run a closed test with ≥12 testers for 14
continuous days before production. Family + friends are those testers. Everything else happens during the 14 days.

| # | Step | Who | Target |
|---|------|-----|--------|
| 1 | Google Play developer account ($25, ID verification takes 1–3 days) | You | Mon 23 Nov |
| 2 | Product name + permanent package ID (can never change after first upload) | You (I propose) | Mon 23 Nov |
| 3 | Release build: R8 shrinking, upload key kept outside the repo, Play App Signing | Me | Mon 23 Nov |
| 4 | ~~Target Android 16 (SDK 36)~~ done v1.5.4 (AGP 8.11.1) | Me | Tue 24 Nov |
| 5 | Streaming-data attribution (JustWatch via TMDB) | Me | Mon 23 Nov |
| 6 | Privacy policy page, Data safety answers, permission justifications | Me (you publish) | Tue 24 Nov |
| 7 | Store listing: name, descriptions, screenshots, feature graphic, icon | Me (you approve) | Wed 25 Nov |
| 8 | Picks for testers: TMDB key built into the closed-test build (stopgap, testers only) | Me | Wed 25 Nov |
| 9 | Closed test live, 12+ testers opted in → 14-day clock starts | You invite, I upload | ~Thu 26 Nov |
| 10 | Server for TMDB + AI (Cloudflare Worker or similar) replaces the built-in key | Me (you create the account) | during the 14 days |
| 11 | Fixes from testers' Diagnostics; at least 3 more LG TVs + 1 Samsung in the test group | Both | during the 14 days |
| 12 | TMDB terms check for launch model (free at launch = non-commercial use with attribution) | You decide | before production |
| 13 | Production release (US, BR, IN, CA, GB, AU) | You press publish | ~Thu 10 Dec |

Not on the launch path (after launch): iPhone app, analytics consent wiring (needs Firebase project), self-calibrating
links, seasonal picks, Spanish/Hindi, licensed where-to-watch data for paid tiers.


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

## Picks: functional issues raised 21 Nov (next)
Bar (user): must beat scrolling through dozens of titles across apps when the TV goes on, or there's no point.
Measure it: time from opening to Play, quick-backs, "Not now" count per session.
1. ~~Only series~~ v0.7.0: balanced series/films for the AI, ≥1 film guaranteed, too-long films swapped for films.
2. ~~3 picks~~ v0.7.2: 5 picks (compact phone cards, 5 across on the TV, NEW tag, "series"/"movie" on every card).
3. ~~All old~~ v0.7.0: recent titles fetched and boosted; "New" mood (last 12 months).
4. Play opens the app, not the title — v0.7.1: Netflix opens the title when Wikidata maps the TMDB ID to a Netflix
   ID (P4947/P4983 → P1874). Three LG launch formats; menu › "Test opening a show" picks the one this TV accepts.
   Then: Disney+, Prime, Hulu, Max (Wikidata has IDs for some; launch formats unknown — test the same way).
5. ~~"Shows you loved"~~ v0.8.0: favourites card per person (until 3, "Later" = a week), TMDB search to add,
   editable in Picks settings; favourites feed "Fans of X also loved it", the AI's taste line, and are never
   offered as picks. Netflix links: formats 1 (api-global contentId) and 3 (https title contentId) open the title
   on our TV; 2 (contentTarget /watch) didn't. Default 1.
- Smaller APK: turn on R8 shrinking before sharing the app (11 MB on 18 Nov → 20 MB now; +3 MB is on-phone AI).

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

## Opening titles (v0.9.0)
- Default for apps without a direct link (Prime, Disney+, Hulu, Max, Apple TV+…): open the app; when its search box
  is selected (TV keyboard opens) the title is typed and Enter pressed automatically (2-minute window).
- Voice order: keyword commands → exact app names → exact title (TMDB, beats loose app sound-alikes) → AI
  (title / person / description / mood) → message saying why ("Barbie is on Max, which isn't one of your
  services"). "play/watch" with nothing found → YouTube, saying so. "search for X on Netflix" → Netflix search page;
  other apps → auto-type.
- Person / description → shown as picks (on their services only).
- Voice: "open / play / watch <title>" (optionally "on <service>") → TMDB search → which of their services has
  it → opened like Play. App names still win. Not on their services → YouTube search (play/watch) or a message.
- "Not it?" bar on the remote for 3 min after opening: Netflix with ID → search page → other format → type it.
  Netflix without ID opens Netflix search directly. Other apps → "type it" (needs the search box selected;
  Enter only when the TV keyboard is open).
- To test on the TV: Search 1/2 (Netflix search page with "The Crown" filled in).
- Next: Prime Video and Disney+ title/search links (same 3-try test).

## v1.4.3 — the phone-network check is advisory only
- "Different network" now warns and still sends the wake-up (Android can report the network oddly: VPN, mesh, /32).
  Only "not on Wi-Fi at all" stops the attempt. Reduces the one new way 1.4.x could fail where 1.3.3 wouldn't.

## v1.4.2 — after the 22 Nov turn-on failures (TV-side, not the app)
- A/B: the 1.3.3 code failed the same way (9 min standby) → not the app version. With the TV on, its network answered
  slowly, timed out and dropped → TV network stuck. Likely trigger: turned off from the app while Netflix was starting
  (suspend mid-launch with Quick Start+); stays stuck because Quick Start+ never fully restarts the TV.
- Protection: turning off (button or voice) while an app is starting (opened < 8 s ago, or play state starting/loaded)
  presses Home first, waits for Home (≤ 2.5 s) + 0.6 s, then turns off. Logged "POWER off while X was starting".
  Not applied to the sleep timer's turn-off (runs mid-show, not mid-launch).
- Unstable TV network: 2+ request timeouts within 30 s → plain message (restart the TV fully) + "NETWORK" in Diagnostics.
- API keys (TMDB, Groq) moved to secrets.xml, excluded from Android cloud backup and device transfer (found when a
  reinstall restored everything). Older keys migrate automatically.
- To confirm on the TV: reproduce (open Netflix, off within 1–2 s, wait 5 min, on) on 1.3.3 vs 1.4.2.

## v1.4.1 — turn-on failed on 1.4.0 (22 Nov, 11:24) — open
- 468 wake packets over 45 s, the TV never appeared on the network. First failed wake in all diagnostics (every
  earlier one worked: ~3–5 s with Quick Start+, ~17 s without). Wake code unchanged since 1.2.1 (diffed).
  User: phone and TV both on Wi-Fi, nothing changed but the app.
- Added: the phone's network is logged at every turn-on ("WAKE phone on Wi-Fi a.b.c.d/24 · TV …"); not on Wi-Fi or on
  another network → said plainly, no wake attempt, no TV help. Before, packets "sent" over mobile data counted as sent.
- A/B: 1.3.3-retest (= 1.3.3 code, versionCode 57) to compare on the same TV after the same standby time.

## v1.4.0 — onboarding (approved 22 Nov)
- Welcome screen on first launch only (never for people who already have a TV): what the app does, "Find my TV".
- Turn-on help only when "Turn on" fails (brother's vivo + LG connected fine without it): the LG / Samsung menu path,
  Try again; after a 2nd failure, the other causes (Wi-Fi off in standby → cable, router/mesh, MAC). Clears itself
  when the TV comes on any other way.
- "No TV found yet": three plain checks, Search again; typing the IP and the technical details are a tap away.
- v1.3.3: found TVs listed as "LG 43UQ7500PSF", not the raw "[LG] webOS TV 43UQ7500PSF".
- Brazilian LG / Samsung menu names for "Turn on via Wi-Fi" are best guesses — confirm on a Brazilian TV.

## v1.3.2 — results of testing 1.2.1 (22 Nov)
- Passed on the TV: power (incl. power right after connecting), LG refactor (remote, voice, timers, picks, picks page).
- Prime: only P1 (contentId = GTI) works: Groundhog Day opened after the profile picker. URL forms landed on another
  show ("Masterplan"). Now P1 only; "Not it?" goes straight to typing. A saved choice that no longer exists falls back.
- Disney+: the old 12-character IDs (P7595/P7596) opened the Disney+ home in every format. Switched to the current
  "browse" ID (entity-<uuid>, Wikidata P13902, ~2,900 titles) with 3 new formats to test (D1 contentId=uuid +
  contentTarget /browse/entity-…, D2 contentTarget only, D3 URL as contentId).
- Voice: "turn on TV" heard as "Tom TV | on TV | turn TV | tongue TV" ran a person search. Now, when no single guess is
  a command, the guesses together are checked for TV + power word (on/off, else toggle).
- "Use a different TV": confirms first, keeps the current TV until the new one pairs, Back on setup returns to it.
- Found by the new simulator test: a new connection was ignored while the previous one was still setting up, and a
  closed connection could reopen itself from a late request (and save its key over the new TV's). Both fixed.
- The usage-sharing question only appears in builds with Firebase set up.

## v1.2.2 / v1.3.1 — testing 1.2.1 from onboarding (22 Nov)
- Fixed: on the LG Home screen the remote jumped to the "watching" view ("Home · playing"). The Home screen's banner
  video and live-TV preview tile (com.webos.app.livedmost) report as playing media; we counted any playing media,
  even from an app not in front. Now only the app in front counts, and never Home / Live TV / inputs / settings.
  Simulator test reproduces the 22 Nov log. Shipped as 1.2.2 (1.2.1 + this fix) for testing.
- Fixed (found by the Samsung simulator, 1 run in 3): a keyboard that opened right after connecting was missed on
  Samsung; the driver now remembers it.
- To fix next: "Use a different TV" forgets the TV immediately with no confirmation and setup has no Back. Should
  confirm, keep the current TV until a new one pairs, and offer Back.
- To look at: voice heard "Tom TV | on TV | turn TV | tongue TV" and ran a person search for "Tom". When an
  alternative looks like a power command ("turn TV", "on TV"), prefer it (or ask) over the AI's person guess.

## v1.3.0 — Portuguese (Brazil) and countries: US, Brazil, India, Canada, UK, Australia (built 22 Nov)
- Settings › Language & country. Language: Automatic (follows the phone) / English / Português (Brasil).
  Country (defaults to the phone's): TMDB where-to-watch region, services offered at setup, content languages
  suggested, speech accent (en-IN, en-GB, en-AU, en-CA; pt-BR in Portuguese), 24 h clock in Brazil and the UK.
- Services added: Globoplay (BR), JioHotstar, Sony LIV (IN), Crave (CA), BBC iPlayer, ITVX, Channel 4, NOW (UK),
  Stan, Binge, ABC iview, SBS On Demand (AU). Their LG app IDs are unknown: the app learns them once the user opens
  the app from the TV. Content languages added: Portuguese, Malayalam.
- Translation: every on-screen string, TV toast, notification and the picks/timer pages on the TV. Reviewed for
  Brazilian CX tone: "você", neutral voice, "na Netflix / no Prime Video", "1h30", "às 21:42". Logs and Diagnostics
  stay English (labels coming back from the TV page are mapped back to English). I18nTest fails if a screen string
  has no Portuguese.
- Titles are never translated by us: the app shows each title as TMDB lists it in the app's language (pt-BR titles
  in Portuguese, e.g. "Round 6"), which is what Netflix etc. show in that country.
- Voice: Portuguese commands (ligar/desligar a TV, volume, mudo, pausar, avançar 30 segundos, canal 5, abrir a
  Netflix, assistir X na Netflix, o que vamos assistir…); AI understands Portuguese and writes reasons in Portuguese.
- Next: native-speaker review on a real phone; Spanish (Mexico) if LatAm grows; Hindi UI for India later.

## Usage analytics (built, waiting on: Firebase project + approval of the consent design)
- Built: Analytics.kt (Firebase Analytics), off until the user says yes (one-time card on the remote, or Settings ›
  Privacy). Never the advertising ID, names, titles, what was said or the TV address. Events: tv_connected (brand,
  model), turn_on (result), picks_opened (how), pick_played (service, direct_X / app_and_type), not_it, quick_back,
  voice_command (kind). User properties: home_country, app_language. Firebase adds country from the network itself.
- To switch on: create a Firebase project (Android app id com.home.tvremote), download google-services.json,
  add it to android/app/. The build picks it up automatically; without it analytics is a no-op (Diagnostics shows
  "not set up in this build").
- Before Play: privacy policy page (what's collected, why, how to opt out), Play Data safety form.

### Earlier notes
- Installs and countries: Google Play Console gives these for free once the app is on Play.
- Per-install usage: an anonymous install ID + a few events (app open, TV brand/model, picks opened/played,
  quick-back, voice command kind, link worked/not). Options: Firebase Analytics (free, Google), PostHog (EU hosting
  option), Aptabase (privacy-first, small). Needs a privacy policy + consent (LGPD in Brazil, UK GDPR).

## v1.2.0 — Prime Video and Disney+ open straight to the title (built 22 Nov, untested on the TV)
- IDs from Wikidata in one query per title: Netflix (P1874), Prime Video GTI (P14462), Disney+ movie/series (P7595/P7596).
- Prime: contentId = amzn1.dv.gti.… (P1, reported working on a 2024 LG on 17 Nov 2026); P2/P3 = primevideo URL as
  contentId / contentTarget. Disney+: no format confirmed anywhere — D1 contentTarget title page + contentId,
  D2 URL as contentId, D3 /video/<id>. "Test opening a show" now has Netflix / Prime Video / Disney+ tabs.
- v1.2.1: if Wikidata never recorded a title's TMDB ID, it's found by the Wikidata item TMDB points to (external_ids).
- Coverage limits: Wikidata has Prime GTIs mostly for films (none for The Boys, Reacher, Fleabag); Disney+ covers many
  films and big series (Mandalorian, Andor). No ID → app opens and we try typing (which may not work, see v1.1.1).
- Better coverage later: a licensed source (JustWatch partner API, or the services' own feeds). JustWatch's public site
  API has all links but its terms don't allow it in a commercial app.

## v1.1.1 — auto-type didn't work in Prime, Disney+, Netflix (reported 22 Nov)
- Likely cause: auto-type waits for the TV to say "a text box is focused" (webOS system keyboard). Netflix, Prime and
  Disney+ draw their own letter grid instead of using the TV's keyboard, so that signal may never come → nothing typed.
- v1.1.1 logs "KEYBOARD opened/closed" and "TYPE waiting…" in Diagnostics to confirm. Quick check without diagnostics:
  when the app's search box is selected, does the phone show its "type on TV" bar? No bar = the app doesn't use the TV keyboard.
- If confirmed: drop "type into search" for those apps and replace it with direct links (Prime, Disney+), the route
  that already works for Netflix. Keep auto-type for apps that do use the TV keyboard (YouTube? browser).

## iOS app (backlog)
- Needs: a Mac with Xcode (or a macOS build machine such as GitHub Actions), an Apple Developer account ($99/yr),
  an iPhone to test on, TestFlight to install builds.
- Code: the Android code is Kotlin and can't run on iPhone as is. Options: (a) rewrite in Swift/SwiftUI;
  (b) Kotlin Multiplatform: share TV drivers, picks ranking, TMDB, voice parsing; write only the screens in SwiftUI.
  (b) keeps one copy of the hard parts and the simulated-TV tests. Recommended.
- Apple approvals to ask for early: the multicast networking entitlement (needed for SSDP discovery and Wake-on-LAN
  broadcast; Apple reviews the request) and the Local Network permission text.
- Differences: no Gemini Nano (use Apple's on-device model on iPhones with Apple Intelligence, else Groq); speech via
  Apple's recognizer; iOS suspends apps in the background, so the sleep timer, the timer page and the picks page on
  the TV need a rethink (Live Activity / notifications, or a small server).
- I can't build iOS in this Linux cloud session: it needs the user's Mac attached, or a macOS CI runner.

## v1.1.0 — Samsung TVs (built 22 Nov, no real Samsung TV yet)
- Done: one TvDriver interface per brand. LG moved behind it unchanged (all LG simulator tests pass as before); new SamsungDriver
  (Tizen remote websocket 8002/8001 with Allow + token, REST 8001 for model / MAC / power / app in front, keys, typing via
  ime events, installed apps, deep-link launch). App IDs translated at the driver edge, so picks / voice / icons are untouched.
- Done: discovery finds Samsung (SSDP RemoteControlReceiver + subnet scan of 8001 confirmed by REST); a typed-in IP is asked
  which brand it is. Brand is saved with the TV. Timer goes through the driver too.
- Done: Simulated Samsung TV + 9 end-to-end tests (pairing, standby → turn on, off from app / own remote, drop → reconnect,
  Netflix deep link, auto-type, touchpad → arrows).
- Fixed (found by the Samsung simulator, affects LG too): pressing power while the app was still asking the TV's power state
  could flip it back to "on". Samsung-only: closing right after KEY_POWER could drop the key.
- Samsung differences: no free pointer (drag = arrows, tap = OK), no play state (no watching view), no on-TV toasts,
  no volume level (only up/down), "in front" polled every 3 s.
- Unverified until someone tries a real Samsung: Netflix metaTag format, Max / Peacock / Paramount+ Tizen IDs,
  2016–2017 models (8001 only, no power state), whether the TV's browser opens the picks / timer pages.

## v1.0.0 — the redesign (8 screens, "one hero per screen")
- Remote browsing: slim "What should we watch?" line, app icons without labels, touchpad, quiet glyph row
  (Back · Home · Timer · Mute), volume + mic. Remote watching (TV reports playing/paused): title + app + state +
  "ends" (from picks), touchpad with double-tap skip icons.
- TV off: one button + "or just say 'turn on the TV'" + mic.
- Choose: six even mood tiles (Surprise is the sixth, dark), "On TV" switch in the corner, mic line instead of a text
  box, Keep watching with ✕ (dismissed per person, for good).
- Picks: rows (poster, meta, reason); tap = play, swipe left = Not now, hold = Never (confirm). "5 more" / "Other mood".
- Settings replaces the menu under the TV name (This TV / Picks / More); Help & diagnostics holds the tools,
  phrased as problems. Messages can carry an action ("Add" → services for "on Max — not one of your services").
- Picks rows in Settings (People/Favourites/Services/Bedtimes/Advanced) still open the existing Picks settings page.

## v0.9.9
- Skipping is a gesture now (user + "Jony" choice): double-tap the left/right half of the touchpad while watching
  (keep tapping for more, ripple shows ±N s); faint ⟲ 10 / 10 ⟳ at the edges. Skip-button row removed (touchpad
  was shrinking). Single tap waits ~0.26 s to rule out a double.
- People in any phrasing, checked against TMDB: "show me some Adam Sandler movies", "Pedro Pascal shows", "movies by
  Christopher Nolan" (directors/creators included). "some funny movies" / "a thriller" → picker, not YouTube.
- Max message: "on Max, but the remote hasn't seen the Max app on this TV yet — open Max once" (was "not one of
  your services" when the app ID wasn't known).

## v0.9.8 (dump of 21 Nov 19:30, v0.9.6)
- Worked: Stranger Things (no more "Stranger"), Breaking Bad person, Aniston films → Dumplin' played in 13 s, Not it.
- Max app on our LG is com.wbd.stream (wasn't recognised) → added; user hasn't added Max as a service.
- "play Barbie the movie" → YouTube: "the movie"/"(2023)" broke title lookup → stripped; year picks the version.
- To confirm: skip buttons / timeline click in Max (3 LEFT + 2 clicks logged while playing).

## Simulated LG TV (v0.9.7) — every build must pass it before reaching the phone
- SimulatedLgTv (test code): speaks the TV's protocol over a real socket, scripted from our TV's Diagnostics —
  pairing, power states incl. Quick Start+ standby that never answers pairing, app in front + play state, launch
  replies, keyboard focus, pointer socket, TV-side drops, Wake-on-LAN. TvController talks to it through TvController.Net.
- SimulatedTvTest: 8 end-to-end scenarios (~24 s): TV on at open; QS+ standby at open → Turn on → stays on; TV-remote
  on/off followed; turn off from app; drop → auto-reconnect; Netflix link sent; title typed when search box opens;
  tap pauses / pointed tap clicks / skip = arrows.
- Found on first run: QS+ standby at app open waited 8 s then showed "No answer… accept the prompt" instead of
  Turn on → fixed (paired + silent = off; 4 s pairing wait for paired TVs). Also: TV closing the connection is now a
  drop straight away.
- Can't simulate: inside apps (Netflix/Prime/Disney+ pages, profiles, their keyboards), LG's browser, speech.

## v0.9.6 (tests of 21 Nov evening, user was still on 0.9.4)
- People: credits were talk shows and guest spots (Bryan Cranston → Family Guy, Seinfeld…; Aniston's 13 talk shows
  crowded out her films). Now real roles only (no "Self"/host, TV ≥ 4 episodes, film billing ≤ 8), most-voted first,
  film/series filter before cutting, availability checked in parallel. "A and B" = titles with both (AI arrays too).
  Live: Cranston → Breaking Bad, John Carter, Total Recall…; Aniston (films) → Murder Mystery 1 & 2, Dumplin'.
- "play Barbie": exact title match wins → "Barbie is on Max, which isn't one of your services — add Max…" (was opening
  Barbie: Dreamhouse Adventures / YouTube). YouTube only for things that aren't shows or films.
- "open Stranger Things" opened "Stranger" (from the guess "open stranger thanks" → "thanks" stripped). Now: your first
  guess exactly, then other guesses exactly (skipping ones that are part of it), then loosely.
- "play/watch … with <person>" goes to people, not titles.
- Clicking Netflix's timeline paused instead: taps while watching after pointing now send a real click, not OK.

## v0.9.5: skipping while watching
- ⟲ 10 s / 10 s ⟳ buttons above the touchpad while something plays (hold = keep skipping; LEFT/RIGHT arrows).
- Voice: "skip 5 minutes", "go back 30 seconds" (10-s arrow presses, max 10 min). Jump to a spot: pause, point at the
  app's timeline on the TV, tap (to confirm on the TV).
- Diagnostics: "Favourites: Shankha 3 · Priya 0 · …" and who still gets the card.
- Phone timing confirmed (v0.9.4): picks ready 0.5–1.0 s; Gemini Nano reasons 10.7–11.6 s (in the background).

## Speed (v0.9.4): picks took ~10 s on the phone
- Ranking now chooses; the AI only rewrites the reasons afterwards (same picks, same order). Details for the 5 picks
  fetched in parallel. Mood-independent data (providers, trending, favourites' "also liked", genres) cached and
  warmed when the picker opens / the person changes.
- Live TMDB from the cloud box: cold 1.4 s, after warm-up 0.4 s. Diagnostics: "PICKS ready in … ms (catalog+ranking …
  · details …)" and "PICKS reasons by … in … ms".
- Opening search inside apps: no cross-app way to pick a profile or open search; only per-app remote-button
  sequences (fragile). Better: per-app title IDs (Wikidata has Disney+ / Prime Video IDs) → open the show directly.

## Test results 21 Nov (v0.9.2) → v0.9.3
- Power with QS+: all passed (app on/off, TV-remote on/off noticed). Picks: "way better".
- Netflix: Try 1 (api-global contentId) and Try 3 (https title contentId) open the show; search links (S1, S2) don't →
  removed; Netflix without an ID now opens the app and auto-types like other apps.
- Voice fixes: picker voice runs commands/titles first (was always "new picks"); "with <person>" handled without AI
  (AI had labelled it a title and invented films); a named person beats the AI's title list; all recognizer guesses
  tried for titles ("the crown" heard as "chrome"). HEARD/PICKER/VOICE lines in Diagnostics.
- Connection drops whenever the app goes to the background; reconnects in ~0.2 s on return (fine).
- Not yet tested: Prime / Disney+ auto-type, "play Barbie".

## Quick Start+ ON (21 Nov): wake from the app now ~5.6 s (was ~26 s)
- v0.9.2: with QS+ an off TV stays reachable, so "reachable" ≠ "on". Bug: the app-open connect to a standby TV hung,
  then failed after turn-on had already connected, and flipped the screen back to "Turn on" (user saw ~5 min of
  "off" while links still worked). Fix: stale attempts can't overwrite newer state; power state asked from the TV;
  live power subscription (on/off by any remote); auto-reconnect after a drop (5 tries); Diagnostics log kept
  across restarts with APP/CHECK/CONNECT/DROPPED/RECONNECT/POWER lines.
- Netflix link tests with QS+ on: user thinks none worked (earlier 1 and 3 did). Re-test from a fresh Home screen.

## Ranking (v0.9.1, tuned on live TMDB — LiveRankingReport, run with TMDB_KEY set)
- Candidates in parallel: mood on their services (popular + first released/aired in 2 years), plus "fans also
  liked" of favourites/likes that fit the mood and are on their services (checked live).
- Score = rating shrunk to 6.6 until ~300 votes + trending this week + small popularity + recency (only with
  enough votes and ★7+) − old long-running series − cartoons unless favourites include animation + taste
  (fans of favourites, shared genres). Titles under 80 votes (40 if new) dropped.
- Moods: the mood's genre must be the title's main genre (first for Light/Cozy); kids/news/reality/talk/soap never.
- Variety: re-ranked so the same main genre, two cartoons or the same franchise don't fill a set.
- Results (36 sets): films 14%→26%, oldest pick 1989→1994, "new" titles that are really new, "Fans of…" 9→17,
  each mood distinct; ~0.7 s per set before AI.
- Known: TMDB genre tags are loose (Forrest Gump = comedy; BEEF = comedy first).

## Works on other people's LG TVs (before sharing)
- Netflix link format: self-calibrating instead of the test menu — after a Netflix Play, ask once "Did it open
  <title>?"; No → next variant next time; remember per TV. Same for other apps as their formats are found.
- Ship a table of known-good formats per app × webOS version; seed from our TV, grow from testers' Diagnostics.
- App IDs differ by region/firmware (e.g. Max, hoichoi): already learned from what the TV lists/opens; keep
  the "open it once from the TV" fallback.
- Region: TMDB region is fixed to US; make it a setting (availability + providers differ per country).
- Test matrix: at least 3 more LG TVs across webOS versions (2020–2025); pairing, Wake-on-LAN, pointer, links.
- Keys: no per-user TMDB/Groq keys in a shared app → our small server (see Before selling).

## v1.5.4 — Android 16 (23 Nov)
- targetSdk/compileSdk 36, AGP 8.11.1. Unit-test runs compile against 35: Paparazzi 1.3.5 can't render with the 36
  platform (it copies Build fields from its Android 15 renderer); newer layoutlib fails "init Bridge" and Paparazzi
  2.0 alphas need AGP 9. Revisit when Paparazzi 2 is stable.
- Flaky (seen once in 4 runs): SimulatedTvTest "Dispatchers.Main is used concurrently with setting it" — two tests
  set Main in parallel. Fix by setting Main once per class.

## v1.5.3 — rent or buy everywhere they ask for something specific (23 Nov)
- Why rent/buy never showed: the app was built on "only what's in your subscriptions" — TMDB's rent/buy lists were
  never read. 1.5.2 fixed asking for a title by name; 1.5.3 extends it to a person ("Nolan movies") and to a
  description ("the one where a guy relives the same day"): their services first, then "· To rent or buy on Prime
  Video". A single described match that's only to rent/buy is shown, not opened, so they see it costs money.
- Mood picks stay subscription-only (deliberate).
- Rent/buy stores come from the same TMDB request as the subscription check (no extra calls).
- I18n fix: a pattern like "With {}." swallowed "· To rent or buy on …" and left it in English; parts now win.
- Still open: a misheard name only gets the rent card on an exact match.

## v1.5.2 — rent or buy titles are shown (22 Nov)
- A title on none of their services but rented/sold by a store with an app on the TV (Prime Video, Apple TV,
  YouTube, Fandango at Home) is shown as a pick: "To rent or buy on Prime Video. Also on Max." Play opens that store
  (Prime Video first when they have Prime). Only when no such store app is on the TV does it fall back to the message.
- Picks from moods still only use their subscriptions.

## v1.5.1 — "The Odyssey Christopher Nolan" (22 Nov)
- The AI answered kind "title|person" and the "person wins" rule listed Nolan's other films. Now a title the user
  actually said wins; the person only chooses among same-named titles (6 × "The Odyssey"). It's buy-only in the US,
  so the answer is "isn't on a subscription service in the United States (only to rent or buy)".
- Prompt: exactly one kind; titles in the language spoken (one answer came back as "Odisseia").
- Open question: a mic on the picks results screen (never had one; only the mood screen and "On TV" mode do).

## Decision (22 Nov): one small server for TMDB + AI; no bundled model
- The server holds the TMDB access (the real blocker for other users) and the AI key; AI only where it helps
  (understanding requests that need world knowledge, writing reasons). Ranking stays on the phone, no AI.
- Phone keeps: on-phone AI where free (Gemini Nano; Apple's on-device model on iPhone) and rule-based reasons
  as the always-works fallback. No bundled open-weights model (+0.6–1 GB, weak film knowledge, budget phones).

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
- v0.6.3: pointer on blank space (for 0.25 s) → phone says "Not on a button" and the TV drops the highlight; OK
  clicks only what's under the pointer; every tap is logged with what it landed on (to explain the 3 of 6 taps
  that selected nothing in v0.6.2).
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
