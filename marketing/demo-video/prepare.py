"""Run from work/: rotates the room photo upright and builds the timer-sheet phone frame."""
from PIL import Image, ImageOps
ImageOps.exif_transpose(Image.open("room.jpg")).convert("RGB").save("room_up.jpg", quality=95)
P = "com.home.tvremote_ScreenshotTest_"
r = Image.open(P + "demoRemote.png").convert("RGBA"); sh = Image.open(P + "timerSheetLight.png").convert("RGBA")
o = Image.alpha_composite(r, Image.new("RGBA", r.size, (0, 0, 0, 0x99))); top = 552 * sh.height // 1000
o.paste(sh.crop((0, top, sh.width, sh.height)).resize((r.width, r.height - top)), (0, top))
o.convert("RGB").save(P + "demoTimerOpen.png"); print("ok")
