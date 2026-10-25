#!/usr/bin/env python3
"""Play sürümünün bileşen modüllerini hazırlar (PLAN Faz 19, 19.3–19.4).

Play sürümü (targetSdk 36) uygulama klasörüne indirilen programları çalıştıramaz (W^X); çalıştırabildiği tek yer
Android'in uygulamayla birlikte kurduğu yerel kitaplık klasörüdür (`nativeLibraryDir`). Bu betik bileşen deposundaki
(components.json) paketleri indirir, sha256'yı doğrular ve her paketin çalıştırılabilir dosyalarını Play modülünün
yerel kitaplığı olarak yazar: `bin/cloudflared` -> `jniLibs/<abi>/libcloudflared.so` (Android yalnızca `lib*.so`
adlı dosyaları kurar). Paket tanımları da Play sürümünün kataloğu olarak yazılır: modüllerdeki programlarla aynı
sürümü anlatsın diye katalog uygulamanın içinde gelir, depodan okunmaz.

Kullanım: tools/play-components.py [bileşen ...]     (varsayılan: cloudflared)
Ortam:    COMPONENTS_URL=<components.json adresi>
Çıktı:    build/play-components/<id>/jniLibs/<abi>/lib<ad>.so ve build/play-components/catalog/play-components.json
"""
import hashlib
import io
import json
import os
import sys
import urllib.request
import zipfile

URL = os.environ.get("COMPONENTS_URL", "https://github.com/kasimtmc/droidserver/releases/download/components/components.json")
ABIS = ["arm64-v8a", "armeabi-v7a", "x86_64"]  # Play sürümünün ABI'leri (app/build.gradle.kts, play türü)
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "build", "play-components")


def fetch(url):
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def lib_name(executable):
    return "lib" + executable.rsplit("/", 1)[-1] + ".so"


def main(ids):
    packages = {p["id"]: p for p in json.loads(fetch(URL))["packages"]}
    catalog = []
    for cid in ids:
        pkg = packages.get(cid) or sys.exit(f"Depoda '{cid}' yok: {URL}")
        names = [lib_name(e) for e in pkg["executables"]]
        if len(set(names)) != len(names):
            sys.exit(f"{cid}: çalıştırılabilir dosya adları çakışıyor: {names}")
        for abi in ABIS:
            build = pkg["builds"].get(abi) or sys.exit(f"{cid}: {abi} derlemesi yok")
            data = fetch(build["url"])
            if hashlib.sha256(data).hexdigest().lower() != build["sha256"].lower():
                sys.exit(f"{cid} {abi}: sha256 uyuşmuyor")
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                files = [n for n in z.namelist() if not n.endswith("/")]
                # Veri dosyaları (Postgres share/, Python kitaplığı) varlık olarak 19.4'te gelecek.
                extra = sorted(set(files) - set(pkg["executables"]))
                if extra:
                    sys.exit(f"{cid}: çalıştırılabilir olmayan dosyalar henüz desteklenmiyor: {extra[:5]}")
                out = os.path.join(ROOT, cid, "jniLibs", abi)
                os.makedirs(out, exist_ok=True)
                for exe in pkg["executables"]:
                    with open(os.path.join(out, lib_name(exe)), "wb") as f:
                        f.write(z.read(exe))
            print(f"{cid} {pkg['version']} {abi}: {', '.join(names)}")
        catalog.append(pkg)
    out = os.path.join(ROOT, "catalog")
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "play-components.json"), "w") as f:
        json.dump({"packages": catalog}, f, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main(sys.argv[1:] or ["cloudflared"])
