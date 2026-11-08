"""Scarica i modelli da tools/sources.json, calcola SHA-256 e dimensione, li copia nella release
del repository (riserva se Hugging Face non risponde) e rigenera models.json.
Stampa anche ingressi e uscite di ogni .onnx per controllare che il motore dell'app li gestisca."""
import hashlib, json, os, subprocess, sys, urllib.request

repo = os.environ["GITHUB_REPOSITORY"]
tag = os.environ.get("MODELS_TAG", "models-v1")
src = json.load(open("tools/sources.json"))
os.makedirs("dl", exist_ok=True)

def inspect(path):
    try:
        import onnx
        m = onnx.load(path, load_external_data=False)
        def shape(t):
            return [d.dim_value or d.dim_param or "?" for d in t.type.tensor_type.shape.dim]
        init = {i.name for i in m.graph.initializer}
        print("  ingressi:")
        for i in m.graph.input:
            if i.name not in init:
                print("   ", i.name, shape(i), onnx.TensorProto.DataType.Name(i.type.tensor_type.elem_type))
        print("  uscite:")
        for o in m.graph.output:
            print("   ", o.name, shape(o), onnx.TensorProto.DataType.Name(o.type.tensor_type.elem_type))
        print("  opset:", [(x.domain or "ai.onnx", x.version) for x in m.opset_import])
    except Exception as e:
        print("  (ispezione non riuscita)", e)

out = {"models": []}
paths = []
for m in src["models"]:
    files = []
    for f in m["files"]:
        path = os.path.join("dl", f["name"])
        print(f"Scarico {f['source']}")
        req = urllib.request.Request(f["source"], headers={"User-Agent": "MLStems-CI"})
        h = hashlib.sha256(); size = 0
        try:
            r = urllib.request.urlopen(req)
        except Exception as e:
            print("  ERRORE", e)
            parts = f["source"].split("/")
            if "huggingface.co" in parts[2]:
                repo_id = parts[3] + "/" + parts[4]
                try:
                    api = urllib.request.Request(f"https://huggingface.co/api/models/{repo_id}", headers={"User-Agent": "MLStems-CI"})
                    info = json.load(urllib.request.urlopen(api))
                    print("  file nel repository", repo_id, ":", [x["rfilename"] for x in info.get("siblings", [])])
                except Exception as e2:
                    print("  elenco non disponibile:", e2)
            sys.exit(1)
        with r, open(path, "wb") as o:
            while True:
                chunk = r.read(1 << 20)
                if not chunk:
                    break
                o.write(chunk); h.update(chunk); size += len(chunk)
        print(f"  {f['name']}: {size} byte, sha256 {h.hexdigest()}")
        inspect(path)
        paths.append(path)
        files.append({
            "name": f["name"], "sha256": h.hexdigest(), "size": size,
            "urls": [f["source"], f"https://github.com/{repo}/releases/download/{tag}/{f['name']}"],
        })
    out["models"].append({"id": m["id"], "version": m["version"], "files": files})

if subprocess.run(["gh", "release", "view", tag], capture_output=True).returncode != 0:
    subprocess.run(["gh", "release", "create", tag, "--title", "Modelli", "--notes",
                    "Copie dei modelli ONNX usate dall'app come riserva (vedi docs/MODELLI.md)."], check=True)
subprocess.run(["gh", "release", "upload", tag, *paths, "--clobber"], check=True)
json.dump(out, open("models.json", "w"), indent=2)
print(json.dumps(out, indent=2))
