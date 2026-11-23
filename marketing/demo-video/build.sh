#!/usr/bin/env bash
# Rebuilds the living-room demo video. Needs: python3 (Pillow, numpy), node + playwright, ffmpeg,
# a TMDB read token (see fetch_art.py) and the room photo at work/room.jpg (not committed).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; APP="$HERE/../../android"; W="$HERE/work"; mkdir -p "$W"; cd "$APP"
./gradlew -q recordPaparazziDebug --tests '*ScreenshotTest.demo*' --tests '*ScreenshotTest.timerSheetLight' --tests '*ScreenshotTest.picksChooseLight'
cp app/src/test/snapshots/images/com.home.tvremote_ScreenshotTest_{demo*,timerSheetLight,picksChooseLight}.png "$W/"
cp app/build/demo-video/{page.html,state_picks.json,state_choose.json,timer.html} "$W/"
cp "$HERE/state_playing.json" "$W/"
cd "$W"
[ -f room.jpg ] || { echo "Put the room photo at $W/room.jpg"; exit 1; }
python3 "$HERE/prepare.py"
python3 "$HERE/fetch_art.py"                # posters, logos, and posters on the picks page
node "$HERE/render_tv.js"                 # the app's picks page: choose / picks / playing
node "$HERE/shoot_timer.js"               # the app's timer page, 10:00 → 9:56
python3 "$HERE/tvframes.py"               # off-screen frame
python3 "$HERE/screens.py" && node "$HERE/shoot.js"   # TV home, apps, search, players
python3 "$HERE/room.py" --sheet           # contact sheet: sheet_room.png — look before the full render
python3 "$HERE/room.py" && python3 "$HERE/soundtrack.py"
ffmpeg -y -loglevel error -i demo_room.mp4 -i music.wav -c:v copy -c:a aac -b:a 192k -shortest -movflags +faststart sofapal-living-room-demo.mp4
echo "done: $W/sofapal-living-room-demo.mp4"
