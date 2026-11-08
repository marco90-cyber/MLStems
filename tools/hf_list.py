"""Elenca file e dimensioni di repository Hugging Face (diagnostica per scegliere le sorgenti dei modelli)."""
import json, sys, urllib.request
for repo in sys.argv[1:]:
    try:
        req = urllib.request.Request(f"https://huggingface.co/api/models/{repo}?blobs=true", headers={"User-Agent": "MLStems-CI"})
        info = json.load(urllib.request.urlopen(req))
        print(f"== {repo}  licenza={info.get('cardData', {}).get('license')}  gated={info.get('gated')}")
        for x in info.get("siblings", []):
            print("   ", x["rfilename"], x.get("size"), (x.get("lfs") or {}).get("sha256", "")[:12])
    except Exception as e:
        print(f"== {repo}  ERRORE {e}")
