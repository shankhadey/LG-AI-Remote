import json
A = "art/"; L = "logos/"
def bd(s): return f"{A}{s}_b.jpg"
def lg(s): return f"{A}{s}_l.png"
def pst(s): return f"{A}{s}_p.jpg"
CSS = """*{margin:0;padding:0;box-sizing:border-box}body{width:1920px;height:1080px;overflow:hidden;font-family:Inter,Roboto,sans-serif;color:#fff;background:#0b0c10}
.row{display:flex;gap:26px}.lab{font-size:30px;font-weight:600;color:#e8e8ee;margin:0 0 18px}
.tile{position:relative;width:400px;height:225px;border-radius:14px;overflow:hidden;background-size:cover;background-position:center;flex:none}
.tile .tl{position:absolute;left:18px;bottom:22px;max-width:62%;max-height:42%;object-fit:contain;object-position:left bottom;filter:drop-shadow(0 2px 6px rgba(0,0,0,.6))}
.tile:after{content:'';position:absolute;inset:0;background:linear-gradient(0deg,rgba(0,0,0,.55),transparent 55%)}
.tile .tl,.tile .pb{z-index:2}.pb{position:absolute;left:18px;right:18px;bottom:10px;height:6px;border-radius:3px;background:rgba(255,255,255,.3)}.pb i{display:block;height:100%;border-radius:3px;background:#e50914}
.hero{position:absolute;left:0;top:0;width:1920px;height:640px;background-size:cover;background-position:center 25%}
.hero:after{content:'';position:absolute;inset:0;background:linear-gradient(90deg,rgba(8,8,12,.85) 0%,rgba(8,8,12,.2) 55%,transparent),linear-gradient(0deg,#0b0c10 0%,transparent 45%)}
.hl{position:absolute;z-index:2}.btn{display:inline-flex;align-items:center;gap:14px;padding:16px 34px;border-radius:10px;font-size:30px;font-weight:700}
.poster{width:230px;height:345px;border-radius:12px;background-size:cover;background-position:center;flex:none}"""
def tile(s, prog=None, id_=None):
    p = f'<div class="pb"><i style="width:{int(prog*100)}%"></i></div>' if prog else ""
    return f'<div class="tile" {f"data-id={id_}" if id_ else ""} style="background-image:url({bd(s)})"><img class="tl" src="{lg(s)}">{p}</div>'
def page(body, bg="#0b0c10", extra=""): return f"<html><head><style>{CSS}{extra}</style></head><body style='background:{bg}'>{body}</body></html>"
def app(name, bg, logo, pad="28px 40px", h=None, white=False):
    fl = "filter:brightness(0) invert(1);" if white else ""
    return f'<div class="app" style="background:{bg}"><img src="{logo}" style="{fl}max-width:72%;max-height:{h or 46}%"></div>' if logo else f'<div class="app" style="background:{bg}"><span>{name}</span></div>'

# --- LG-style home ---
apps = (app("Netflix", "#000", L + "Netflix_2015_logo.svg", h=34) + app("YouTube", "#fff", L + "YouTube_Logo_2017.svg", h=34) + app("Prime Video", "#00050d", L + "Amazon_Prime_Video_logo.svg", h=40)
        + app("Disney+", "linear-gradient(135deg,#0b1a5c,#071033)", L + "Disney+_logo.svg", h=52, white=True) + app("Apple TV", "#000", L + "Apple_TV_Plus_Logo.svg", h=38, white=True) + app("Live TV", "#262a33", None) + app("Web Browser", "#262a33", None))
home = page(f"""<div class="hero" style="background-image:url({bd('DunePartTwo')})"></div>
<div class="hl" style="left:90px;top:70px;font-size:26px;color:#ddd;display:flex;gap:40px;align-items:center"><span style="font-weight:700;font-size:30px;color:#fff">Home</span><span>Search</span><span>Apps</span><span>Sports</span></div>
<div class="hl" style="right:90px;top:66px;font-size:30px;color:#eee">8:04 PM</div>
<div class="hl" style="left:90px;top:200px"><img src="{lg('DunePartTwo')}" style="max-width:520px;max-height:170px"><div style="font-size:26px;color:#ddd;margin-top:22px">Now streaming · Rent or buy</div></div>
<div class="hl" style="left:90px;top:560px"><div class="row apps">{apps}</div></div>
<div class="hl" style="left:90px;top:760px"><div class="lab">Continue watching</div><div class="row">{tile('Severance',.62,'c0')}{tile('TheBear',.35,'c1')}{tile('Shōgun',.8,'c2')}{tile('SlowHorses',.2,'c3')}</div></div>""",
 extra=".app{width:200px;height:150px;border-radius:16px;display:flex;align-items:center;justify-content:center;flex:none;box-shadow:0 6px 18px rgba(0,0,0,.4)}.app span{font-size:26px;font-weight:600;color:#e6e6ec}.apps{gap:22px}")

def service(logo, lh, bg, hero, sub, rows, accent="#fff", nav=("Home", "Shows", "Movies", "My List")):
    r = "".join(f'<div class="lab" style="margin-top:26px">{lab}</div><div class="row">{"".join(tile(s) for s in ss)}</div>' for lab, ss in rows)
    return page(f"""<div class="hero" style="background-image:url({bd(hero)})"></div>
<div class="hl" style="left:90px;top:60px;display:flex;align-items:center;gap:56px"><img src="{logo}" style="height:{lh}px">{''.join(f'<span style="font-size:26px;color:#ddd">{n}</span>' for n in nav)}</div>
<div class="hl" style="left:90px;top:190px"><img src="{lg(hero)}" style="max-width:560px;max-height:190px"><div style="font-size:26px;color:#ddd;margin:22px 0 30px">{sub}</div>
<span class="btn" style="background:#fff;color:#000">▶&nbsp; Play</span>&nbsp;&nbsp;<span class="btn" style="background:rgba(120,120,130,.6)">More info</span></div>
<div class="hl" style="left:90px;top:640px">{r}</div>""", bg=bg).replace("background:#0b0c10}", f"background:{bg}}}")
netflix = service(L + "Netflix_2015_logo.svg", 54, "#000", "StrangerThings", "Season 5 · Now on Netflix", [("Popular on Netflix", ["Wednesday", "SquidGame", "Bridgerton", "TheCrown"])])
prime = service(L + "Amazon_Prime_Video_logo.svg", 60, "#0f171e", "TheBoys", "Included with Prime", [("Amazon Originals", ["Reacher", "Fallout", "Invincible", "TheLordoftheRingsTheRingsofPower"])], nav=("Home", "Movies", "TV Shows", "Live TV"))
def ytcard(s, title, ch):
    return f'<div style="width:400px;flex:none"><div class="tile" style="background-image:url({bd(s)});height:225px"></div><div style="font-size:24px;font-weight:600;margin-top:14px;line-height:1.25">{title}</div><div style="font-size:21px;color:#aaa;margin-top:6px">{ch}</div></div>'
nav = '<div class="hl" style="left:0;top:0;width:120px;height:1080px;background:#0f0f0f;display:flex;flex-direction:column;align-items:center;gap:56px;padding-top:150px;font-size:34px;color:#aaa"><span>⌕</span><span style="color:#fff">⌂</span><span>▤</span><span>☰</span></div>'
yt = page(nav + f"""<div class="hl" style="left:190px;top:60px"><div style="display:flex;align-items:center;gap:12px"><svg width="78" height="56" viewBox="0 0 78 56"><rect width="78" height="56" rx="14" fill="#ff0000"/><path d="M31 16 L54 28 L31 40Z" fill="#fff"/></svg><span style="font-size:46px;font-weight:700;letter-spacing:-1.5px;color:#fff">YouTube</span></div></div>
<div class="hl" style="left:190px;top:180px"><div class="lab">Recommended</div><div class="row">{ytcard('DunePartTwo','Dune: Part Two | Official Trailer 3','Warner Bros. Pictures')}{ytcard('TopGunMaverick','Top Gun: Maverick | Behind the scenes','Paramount Pictures')}{ytcard('InsideOut2','Inside Out 2 | Official Trailer','Pixar')}{ytcard('Wicked','Wicked | Official Trailer 2','Universal Pictures')}</div>
<div class="lab" style="margin-top:44px">Trending</div><div class="row">{ytcard('Oppenheimer','Oppenheimer | New Trailer','Universal Pictures')}{ytcard('Barbie','Barbie | Main Trailer','Warner Bros. Pictures')}{ytcard('GlassOnion','Glass Onion | Official Trailer','Netflix')}{ytcard('Fallout','Fallout | Official Trailer','Prime Video')}</div></div>""", bg="#0f0f0f")
keys = "".join(f'<div class="k">{c}</div>' for c in "abcdefghijklmnopqrstuvwxyz1234567890")
search = page(nav + f"""<div class="hl" style="left:190px;top:60px"><div style="display:flex;align-items:center;gap:12px"><svg width="78" height="56" viewBox="0 0 78 56"><rect width="78" height="56" rx="14" fill="#ff0000"/><path d="M31 16 L54 28 L31 40Z" fill="#fff"/></svg><span style="font-size:46px;font-weight:700;letter-spacing:-1.5px;color:#fff">YouTube</span></div></div>
<div class="hl" data-id="box" style="left:190px;top:170px;width:1640px;height:96px;border-radius:48px;background:#272727;border:3px solid #555;display:flex;align-items:center;padding-left:36px;font-size:40px;color:#888">⌕</div>
<div class="hl" style="left:190px;top:330px;width:620px;display:grid;grid-template-columns:repeat(6,92px);gap:12px">{keys}</div>
<div class="hl" style="left:900px;top:330px;width:930px"><div class="lab" style="color:#aaa">Try searching for</div><div style="font-size:30px;color:#ccc;line-height:2.1">lofi beats to relax<br>cozy fireplace 4k<br>christmas jazz playlist<br>snow falling ambience</div></div>""",
 bg="#0f0f0f", extra=".k{height:80px;border-radius:12px;background:#212121;display:flex;align-items:center;justify-content:center;font-size:34px;color:#bbb}")
show = page(f"""<div style="position:absolute;inset:0;background:url({bd('TedLasso')}) center/cover"></div>
<div style="position:absolute;inset:0;background:linear-gradient(0deg,rgba(0,0,0,.75),transparent 40%)"></div>
<div class="hl" style="left:90px;bottom:150px"><img src="{lg('TedLasso')}" style="max-height:110px;max-width:420px"><div style="font-size:28px;margin-top:16px;color:#eee">S1 · E1 &nbsp;Pilot</div></div>
<div class="hl" style="left:90px;right:90px;bottom:80px;height:8px;border-radius:4px;background:rgba(255,255,255,.3)"><i style="display:block;width:3%;height:100%;background:#fff;border-radius:4px"></i></div>""", bg="#000")
for n, h in dict(home=home, netflix=netflix, prime=prime, youtube=yt, search=search, show=show).items(): open(f"html_{n}.html", "w").write(h)
print("ok")

yl = '<div style="display:flex;align-items:center;gap:12px"><svg width="78" height="56" viewBox="0 0 78 56"><rect width="78" height="56" rx="14" fill="#ff0000"/><path d="M31 16 L54 28 L31 40Z" fill="#fff"/></svg><span style="font-size:46px;font-weight:700;letter-spacing:-1.5px;color:#fff">YouTube</span></div>'
# ---- search: typed text + matching suggestions, then results ----
Q = "dune trailer"
SUG = ["dune trailer", "dune part two trailer", "dune trailer 3", "dune prophecy trailer", "dune 2021 trailer", "dune part two final trailer"]
def searchpage(q, results=False):
    box = f'<div class="hl" style="left:190px;top:170px;width:1640px;height:96px;border-radius:48px;background:#272727;border:3px solid {"#fff" if not results else "#555"};display:flex;align-items:center;padding-left:36px;font-size:40px;color:#fff"><span style="color:#aaa;margin-right:22px">⌕</span>{q}{"" if results else "<span style=\'display:inline-block;width:3px;height:46px;background:#fff;margin-left:4px\'></span>"}</div>'
    if not results:
        sug = [s for s in SUG if s.startswith(q)] if q else ["lofi beats to relax", "cozy fireplace 4k", "christmas jazz playlist", "snow falling ambience"]
        lab = "Suggestions" if q else "Try searching for"
        right = f'<div class="hl" style="left:900px;top:330px;width:930px"><div class="lab" style="color:#aaa">{lab}</div>' + "".join(
            f'<div style="font-size:32px;color:#eee;line-height:2.1"><span style="color:#888">⌕&nbsp; </span><b>{s[:len(q)]}</b>{s[len(q):]}</div>' for s in sug[:5]) + "</div>"
        body = box + f'<div class="hl" style="left:190px;top:330px;width:620px;display:grid;grid-template-columns:repeat(6,92px);gap:12px">{keys}</div>' + right
    else:
        R = [("dune0", "Dune: Part Two | Official Trailer 3", "Warner Bros. Pictures · 41M views", "2:58"), ("dune1", "Dune: Part Two | Official Trailer", "Warner Bros. Pictures · 33M views", "3:04"),
             ("dune4", "Dune | Official Main Trailer", "Warner Bros. Pictures · 46M views", "3:03"), ("dune6", "Dune: Prophecy | Official Trailer", "HBO Max · 9.8M views", "2:31"),
             ("dune2", "Dune: Part Two | Final Trailer", "Warner Bros. Pictures · 12M views", "2:20"), ("dune5", "Dune | Official Trailer 2", "Warner Bros. Pictures · 21M views", "2:39"),
             ("dune3", "Dune: Part Two | Behind the Scenes", "Warner Bros. Pictures · 4.1M views", "6:12"), ("dune0", "Dune: Part Two | Trailer Reaction", "Movie Night Club · 880K views", "14:47")]
        cards = "".join(f'<div style="width:390px;flex:none"><div class="tile" style="background-image:url(art/{s}.jpg);width:390px;height:219px"><span style="position:absolute;right:10px;bottom:10px;z-index:3;background:rgba(0,0,0,.85);font-size:20px;padding:2px 8px;border-radius:5px">{d}</span></div><div style="font-size:24px;font-weight:600;margin-top:12px;line-height:1.25">{t}</div><div style="font-size:20px;color:#aaa;margin-top:6px">{c}</div></div>' for s, t, c, d in R)
        body = box + f'<div class="hl" style="left:190px;top:320px;width:1660px;display:flex;flex-wrap:wrap;gap:40px 28px">{cards}</div>'
    return page(nav + f'<div class="hl" style="left:190px;top:60px">{yl}</div>' + body, bg="#0f0f0f", extra=".k{height:80px;border-radius:12px;background:#212121;display:flex;align-items:center;justify-content:center;font-size:34px;color:#bbb}")
for i in range(len(Q) + 1): open(f"html_search{i}.html", "w").write(searchpage(Q[:i]))
open("html_results.html", "w").write(searchpage(Q, True))
def player(slug, logo, lh, ep, prog):
    return page(f"""<div style="position:absolute;inset:0;background:url({bd(slug)}) center/cover"></div>
<div style="position:absolute;inset:0;background:linear-gradient(0deg,rgba(0,0,0,.8),transparent 42%),linear-gradient(180deg,rgba(0,0,0,.5),transparent 20%)"></div>
<div class="hl" style="left:90px;top:60px"><img src="{logo}" style="height:{lh}px;filter:brightness(0) invert(1)"></div>
<div class="hl" style="left:90px;bottom:150px"><img src="{lg(slug)}" style="max-height:110px;max-width:420px"><div style="font-size:28px;margin-top:16px;color:#eee">{ep}</div></div>
<div class="hl" style="left:90px;right:90px;bottom:80px;height:8px;border-radius:4px;background:rgba(255,255,255,.3)"><i style="display:block;width:{prog}%;height:100%;background:#fff;border-radius:4px"></i></div>""", bg="#000")
open("html_bear.html", "w").write(player("TheBear", L + "Disney+_logo.svg", 70, "S3 · E4 &nbsp;Violet", 35))
open("html_show.html", "w").write(player("TedLasso", L + "Apple_TV_Plus_Logo.svg", 54, "S1 · E1 &nbsp;Pilot", 3))
print("ok")
