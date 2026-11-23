# Demo video: phone remote in a living room

Builds `sofapal-living-room-demo.mp4` (1080×1350, about 47 s). Every TV screen is shown on a real TV in a room photo:
- The phone screens are the app's Paparazzi `demo*` screenshots.
- The picks page and the timer page are the app's own TV pages, rendered in Chromium.
- The TV home screen and the app screens are HTML recreations, using real logos (Wikimedia Commons) and real posters (TMDB).

Read the **Working rule: marketing assets** section in `BACKLOG.md` before changing anything. Write the cause-and-effect script first, then do a viewer pass.

## Run
1. Put the room photo at `work/room.jpg`. `work/` is gitignored, so no personal photo is committed.
   If you use a different photo, update the TV screen corners in `QUAD` in `room.py`.
2. Make a TMDB read token available:
   - In the cloud environment, it's saved as a network secret for api.themoviedb.org.
   - Locally, export `TMDB_TOKEN`.
3. `./build.sh`. Check `work/sheet_room.png`, then the video at `work/sofapal-living-room-demo.mp4`.

## Files
| File | What it does |
|---|---|
| `build.sh` | Runs everything in order |
| `fetch_art.py` | Downloads TMDB posters, backdrops and title logos, plus app logos from Wikimedia, and adds posters to the picks-page states |
| `prepare.py` | Turns the room photo upright and builds the timer-sheet phone frame |
| `screens.py` + `shoot.js` | TV home, Netflix, Prime Video, YouTube (search letter by letter, results), Disney+ and Apple TV+ players |
| `render_tv.js` / `shoot_timer.js` | The app's real picks page and timer page |
| `tvframes.py` | Simple fallback TV frames (`tv_off.png` is still used) |
| `room.py` | The scenes. Each scene is a function of time; `SC` holds the order and durations |
| `soundtrack.py` | The music, with soft taps at the times `room.py` writes to `timing.json` |
| `state_playing.json` | The picks page while opening a title (the tests don't export it) |

Real logos and posters are fine for our own social posts. Don't use them in Play Store screenshots; keep those generic.
