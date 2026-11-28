"""Test only: how much of what is really streamable (per TMDB, per country and service) our Wikidata catalogue covers.
TMDB is used here as the reference, never shipped. Needs network access to api.themoviedb.org (key injected by the proxy or TMDB_TOKEN)."""
import json, os, sys, urllib.request, urllib.parse, random, collections
TOK = os.environ.get("TMDB_TOKEN", "proxy")
def tm(path, **p):
    req = urllib.request.Request("https://api.themoviedb.org/3/" + path + "?" + urllib.parse.urlencode(p),
                                 headers={"Authorization": "Bearer " + TOK, "Accept": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=30))
COUNTRIES = ["US", "BR", "IN", "CA", "GB", "AU"]
SERVICES = {"Netflix": ("Netflix", "netflix"), "Prime Video": ("Amazon Prime Video", "prime"), "Disney+": ("Disney Plus", "disney"),
            "Apple TV+": ("Apple TV Plus", "apple"), "Max": ("Max", "max"), "JioHotstar": ("JioHotstar", "jiohotstar"), "Globoplay": ("Globoplay", "globoplay")}
SVC_KEYS = {"netflix": ["netflix"], "prime": ["prime", "prime_gti"], "disney": ["disney_movie", "disney_series"],
            "apple": ["apple", "apple_show"], "max": ["max"], "jiohotstar": ["jiohotstar"], "globoplay": ["globoplay"]}
cat = {}
for l in open("catalog.jsonl"):
    r = json.loads(l)
    if r.get("tmdb"): cat[r["tmdb"]] = r
print("catalogue titles:", sum(1 for _ in open("catalog.jsonl")), "with TMDB join key:", len(cat))
rows = []
for c in COUNTRIES:
    prov = {}
    for kind in ("movie", "tv"):
        for p in tm(f"watch/providers/{kind}", watch_region=c)["results"]:
            prov.setdefault(p["provider_name"], p["provider_id"])
    for svc, (tname, skey) in SERVICES.items():
        pid = prov.get(tname) or next((v for k, v in prov.items() if k.lower().startswith(tname.lower().split()[0])), None)
        if not pid: continue
        tot = inc = link = 0
        for kind in ("movie", "tv"):
            for page in (1, 2, 3):
                d = tm(f"discover/{kind}", watch_region=c, with_watch_providers=pid, with_watch_monetization_types="flatrate", sort_by="popularity.desc", page=page)
                for t in d["results"]:
                    tot += 1; r = cat.get(f"{kind}/{t['id']}")
                    if r:
                        inc += 1
                        if any(r["svc"].get(k) for k in SVC_KEYS[skey]): link += 1
        if tot: rows.append((c, svc, tot, inc, link)); print(f"{c} {svc:12} top {tot:3}: in catalogue {100*inc/tot:5.1f}%  with {svc} ID {100*link/tot:5.1f}%", flush=True)
json.dump(rows, open("compare_results.json", "w"))
