"""Downloads real posters, backdrops and title logos from TMDB, app logos from Wikimedia Commons,
and adds posters to the picks TV-page states. Run from work/. The TMDB read token is injected by
the cloud environment's network proxy (Authorization: Bearer); locally set TMDB_TOKEN instead."""
import json, os, urllib.request, urllib.parse, time
os.makedirs("art", exist_ok=True); os.makedirs("logos", exist_ok=True)
TOKEN = os.environ.get("TMDB_TOKEN")
def get(u):
    h = {"accept": "application/json"}
    if TOKEN: h["Authorization"] = "Bearer " + TOKEN
    return json.load(urllib.request.urlopen(urllib.request.Request(u, headers=h), timeout=20))
def save(url, fn): open(fn, "wb").write(urllib.request.urlopen(url, timeout=30).read())
TV = ["Stranger Things", "Wednesday", "Squid Game", "Bridgerton", "The Crown", "The Boys", "Reacher", "Fallout", "Invincible",
      "The Lord of the Rings: The Rings of Power", "Ted Lasso", "Severance", "The Mandalorian", "The Bear", "Shōgun", "Slow Horses"]
MV = ["Dune: Part Two", "Oppenheimer", "Barbie", "Inside Out 2", "Top Gun: Maverick", "Wicked", "Glass Onion"]
for kind, names in (("tv", TV), ("movie", MV)):
    for n in names:
        r = get(f"https://api.themoviedb.org/3/search/{kind}?" + urllib.parse.urlencode({"query": n}))["results"][0]
        slug = "".join(c for c in n if c.isalnum())
        imgs = get(f"https://api.themoviedb.org/3/{kind}/{r['id']}/images?include_image_language=en,null")
        logo = next((l["file_path"] for l in imgs.get("logos", []) if l.get("iso_639_1") == "en" and l["file_path"].endswith(".png")), None)
        bd = next((b["file_path"] for b in imgs.get("backdrops", []) if b.get("iso_639_1") is None), r.get("backdrop_path"))
        for path, size, suf in ((r.get("poster_path"), "w500", "p.jpg"), (bd, "w1280", "b.jpg"), (logo, "w500", "l.png")):
            if path: save("https://image.tmdb.org/t/p/" + size + path, f"art/{slug}_{suf}")
        print("art", n)
n = 0   # backdrops used as YouTube "dune trailer" results
for kind, q, y, k in (("movie", "Dune: Part Two", None, 4), ("movie", "Dune", 2021, 2), ("tv", "Dune: Prophecy", None, 1)):
    p = {"query": q, **({"year": y} if y else {})}
    i = get(f"https://api.themoviedb.org/3/search/{kind}?" + urllib.parse.urlencode(p))["results"][0]["id"]
    for b in get(f"https://api.themoviedb.org/3/{kind}/{i}/images?include_image_language=null")["backdrops"][:k]:
        save("https://image.tmdb.org/t/p/w780" + b["file_path"], f"art/dune{n}.jpg"); n += 1
for f in ["Netflix_2015_logo.svg", "YouTube_Logo_2017.svg", "Amazon_Prime_Video_logo.svg", "Disney+_logo.svg", "Apple_TV_Plus_Logo.svg"]:
    req = urllib.request.Request("https://commons.wikimedia.org/wiki/Special:FilePath/" + f, headers={"User-Agent": "DemoVideoBuilder/1.0 (one-off script)"})
    open("logos/" + f, "wb").write(urllib.request.urlopen(req, timeout=30).read()); time.sleep(2)
for fn in ("state_picks.json", "state_choose.json", "state_playing.json"):   # the app shows posters on its TV page when it has a TMDB key
    d = json.load(open(fn))
    for p in d.get("picks") or []:
        kind = "tv" if "series" in p["meta"] else "movie"
        r = get(f"https://api.themoviedb.org/3/search/{kind}?" + urllib.parse.urlencode({"query": p["name"]}))["results"]
        if r and r[0].get("poster_path"): p["poster"] = "https://image.tmdb.org/t/p/w500" + r[0]["poster_path"]
    json.dump(d, open(fn, "w"))
print("ok")
