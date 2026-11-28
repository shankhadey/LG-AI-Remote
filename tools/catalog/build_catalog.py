"""Build Sofapal's own title catalogue from Wikidata (CC0). Two stages, both resumable:
  1. ids.jsonl     - every film/series with an IMDb ID, by kind and half-year, plus every item with a streaming-service ID
  2. catalog.jsonl - details for those items in batches (labels en/pt/hi, genres, languages, countries, runtime, service IDs)
TMDB IDs are stored only as join keys so the catalogue can be compared with TMDB in tests; no TMDB data is copied."""
import json, sys, time, urllib.request, urllib.parse, os
EP = "https://query.wikidata.org/sparql"
UA = "SofapalCatalogBuilder/0.2 (open-source TV remote; catalog build)"
KINDS = {"movie": "wd:Q11424 wd:Q29168811 wd:Q24869 wd:Q506240 wd:Q202866",
         "tv": "wd:Q5398426 wd:Q1259759 wd:Q581714 wd:Q117467246 wd:Q526877"}
SVC = {"netflix": "P1874", "prime": "P8055", "prime_gti": "P14462", "disney_movie": "P7595", "disney_series": "P7596",
       "max": "P8298", "apple": "P9586", "apple_show": "P9751", "jiohotstar": "P11049", "globoplay": "P12356"}
def q(sparql, tries=4):
    for i in range(tries):
        try:
            data = urllib.parse.urlencode({"query": sparql, "format": "json"}).encode()
            req = urllib.request.Request(EP, data=data, headers={"User-Agent": UA, "Content-Type": "application/x-www-form-urlencoded"})
            return json.load(urllib.request.urlopen(req, timeout=120))["results"]["bindings"]
        except Exception as e:
            print("  retry", i, type(e).__name__, str(e)[:80], file=sys.stderr); time.sleep(15 * (i + 1))
    return None
def num(x):
    """Wikidata uses blank nodes for "unknown value"; treat anything non-numeric as missing."""
    try: return int(float(x))
    except (TypeError, ValueError): return None
def val(b, k): return b[k]["value"] if k in b else None
def qid(u): return u.rsplit("/", 1)[1]

def stage_ids(out="ids.jsonl"):
    """Seed IDs with property-anchored queries (date-filtered ones time out on the public endpoint)."""
    done = set()
    if os.path.exists(out):
        for l in open(out): done.add(json.loads(l)["chunk"])
    chunks = {"svc:" + p: f"SELECT ?i WHERE {{ ?i wdt:{prop} ?x. }}" for p, prop in SVC.items()}
    # Every film/series Wikidata maps to TMDB or IMDb, split by notability so each query stays fast.
    for prop, name in (("P4947", "tmdb_movie"), ("P4983", "tmdb_tv")):
        chunks[f"{name}:2+"] = f"SELECT ?i WHERE {{ ?i wdt:{prop} ?x; wikibase:sitelinks ?l. FILTER(?l >= 2) }}"
        chunks[f"{name}:0-1"] = f"SELECT ?i WHERE {{ ?i wdt:{prop} ?x; wikibase:sitelinks ?l. FILTER(?l < 2) }}"
    # Brazilian and Indian titles even when obscure elsewhere.
    for c, name in (("Q155", "BR"), ("Q668", "IN")):
        for t in ("Q11424", "Q5398426", "Q1259759"):
            chunks[f"origin:{name}:{t}"] = f"SELECT ?i WHERE {{ ?i wdt:P495 wd:{c}; wdt:P31 wd:{t}. }}"
    with open(out, "a") as f:
        for c, sparql in chunks.items():
            if c in done: continue
            rows = q(sparql)
            if rows is None: print("SKIP", c, file=sys.stderr); continue
            f.write(json.dumps({"chunk": c, "ids": sorted({qid(val(b, "i")) for b in rows})}) + "\n"); f.flush()
            print(c, len(rows), file=sys.stderr); time.sleep(1)

def stage_details(ids_file="ids.jsonl", out="catalog.jsonl", batch=250):
    have = set()
    if os.path.exists(out):
        for l in open(out): have.add(json.loads(l)["id"])
    chunks = [json.loads(l) for l in open(ids_file)]
    chunks.sort(key=lambda c: c["chunk"].endswith("0-1"))   # notable titles first, obscure last
    todo, seen = [], set(have)
    for c in chunks:
        for x in c["ids"]:
            if x not in seen: seen.add(x); todo.append(x)
    print("details todo", len(todo), file=sys.stderr)
    opt = "\n".join(f"OPTIONAL {{ ?i wdt:{p} ?{k}. }}" for k, p in SVC.items())
    sel = " ".join(f"(SAMPLE(?{k}) AS ?{k}_)" for k in SVC)
    with open(out, "a") as f:
        for s in range(0, len(todo), batch):
            vals = " ".join("wd:" + x for x in todo[s:s + batch])
            rows = q(f"""SELECT ?i (SAMPLE(?t) AS ?type) (SAMPLE(?en) AS ?en_) (SAMPLE(?pt) AS ?pt_) (SAMPLE(?hi) AS ?hi_) (SAMPLE(?links) AS ?links_)
 (MIN(YEAR(?d)) AS ?year) (GROUP_CONCAT(DISTINCT STRAFTER(STR(?g),"entity/");separator="|") AS ?genres)
 (GROUP_CONCAT(DISTINCT STRAFTER(STR(?lang),"entity/");separator="|") AS ?langs)
 (GROUP_CONCAT(DISTINCT STRAFTER(STR(?c),"entity/");separator="|") AS ?countries)
 (SAMPLE(?dur) AS ?dur_) (SAMPLE(?imdb) AS ?imdb_) (SAMPLE(?tm) AS ?tm_) (SAMPLE(?tt) AS ?tt_) {sel}
WHERE {{ VALUES ?i {{ {vals} }} ?i wikibase:sitelinks ?links.
 OPTIONAL {{ ?i wdt:P31 ?t }} OPTIONAL {{ ?i wdt:P577 ?d }}
 OPTIONAL {{ ?i rdfs:label ?en FILTER(LANG(?en)="en") }} OPTIONAL {{ ?i rdfs:label ?pt FILTER(LANG(?pt)="pt-br" || LANG(?pt)="pt") }}
 OPTIONAL {{ ?i rdfs:label ?hi FILTER(LANG(?hi)="hi") }} OPTIONAL {{ ?i wdt:P136 ?g }} OPTIONAL {{ ?i wdt:P364 ?lang }}
 OPTIONAL {{ ?i wdt:P495 ?c }} OPTIONAL {{ ?i wdt:P2047 ?dur }} OPTIONAL {{ ?i wdt:P345 ?imdb }}
 OPTIONAL {{ ?i wdt:P4947 ?tm }} OPTIONAL {{ ?i wdt:P4983 ?tt }} {opt} }} GROUP BY ?i""")
            if rows is None: print("SKIP batch", s, file=sys.stderr); continue
            for b in rows:
                v = {k[:-1] if k.endswith("_") else k: x["value"] for k, x in b.items()}
                name = v.get("en") or v.get("pt") or v.get("hi")
                if not name: continue
                r = {"id": qid(v["i"]), "kind": "tv" if v.get("tt") or v.get("disney_series") or v.get("apple_show") else "movie",
                     "year": num(v.get("year")), "name": name, "name_pt": v.get("pt"), "name_hi": v.get("hi"),
                     "links": int(v.get("links", 0)), "genres": [g for g in v.get("genres", "").split("|") if g],
                     "langs": [g for g in v.get("langs", "").split("|") if g], "countries": [g for g in v.get("countries", "").split("|") if g],
                     "runtime": num(v.get("dur")), "imdb": v.get("imdb"),
                     "tmdb": ("movie/" + v["tm"]) if v.get("tm") else ("tv/" + v["tt"]) if v.get("tt") else None,
                     "svc": {k: v[k] for k in SVC if v.get(k)}}
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
            f.flush(); print("details", s + batch, "/", len(todo), file=sys.stderr); time.sleep(1)

if __name__ == "__main__":
    {"ids": stage_ids, "details": stage_details}[sys.argv[1]]()
