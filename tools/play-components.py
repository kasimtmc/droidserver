#!/usr/bin/env python3
"""Play sürümünün bileşen modüllerini hazırlar (PLAN Faz 19, 19.3–19.4).

Play sürümü (targetSdk 36) uygulama klasörüne indirilen programları çalıştıramaz (W^X); çalıştırabildiği tek yer
Android'in uygulamayla birlikte kurduğu yerel kitaplık klasörüdür (`nativeLibraryDir`). Android oraya yalnızca
`lib*.so` adlı dosyaları düz bir klasöre açar. Bu betik bileşen deposundaki (components.json) paketleri indirir,
sha256'yı doğrular ve paketin her dosyasını (program, kitaplık, veri) Play modülünün yerel kitaplığı olarak yazar:
`lib<id>_<n>.so`. Paketteki yol -> dosya adı eşlemesi de `lib<id>_files.so` olarak modüle girer; uygulama kurulumda
paket klasörünü bu dosyalara sembolik bağlantılarla kurar (ComponentManager). Paket tanımları Play sürümünün kataloğu
olarak yazılır: modüllerdeki dosyalarla aynı sürümü anlatsın diye katalog uygulamanın içinde gelir, depodan okunmaz.

Dosyalar salt okunur kalacağı için GitHub sürümünün kurulumda yaptığı düzeltmeler burada yapılır: zip'te metin dosyası
olarak saklanmış sembolik bağlantılar hedeflerine eşlenir, Termux'un geçici klasör yolu uygulamanınkine çevrilir.

Kullanım: tools/play-components.py [bileşen ...]     (varsayılan: depodaki bütün paketler)
Ortam:    COMPONENTS_URL=<components.json adresi>
Çıktı:    build/play-components/<id>/jniLibs/<abi>/lib<id>_*.so ve build/play-components/catalog/play-components.json
"""
import hashlib
import io
import json
import os
import posixpath
import re
import shutil
import sys
import urllib.request
import zipfile

URL = os.environ.get("COMPONENTS_URL", "https://github.com/kasimtmc/droidserver/releases/download/components/components.json")
ABIS = ["arm64-v8a", "armeabi-v7a", "x86_64"]  # Play sürümünün ABI'leri (app/build.gradle.kts, play türü)
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "build", "play-components")

# Sembolik bağlantı kalıntısının içeriği (bkz. core ZipFiles.kt): göreli yol.
LINK_TEXT = re.compile(rb"^[A-Za-z0-9._+@-]+(/[A-Za-z0-9._+@-]+)*$")
# Termux geçici klasörü ve eski paketlerin yazdığı hali -> uygulamanınki (aynı uzunlukta; bkz. ComponentManager.TMP_PATHS).
TMP_NEW = b"///data/data/com.plyg.droidserver/t"
TMP_OLD = [b"/data/data/com.termux/files/usr/tmp", b"////data/data/dev.droidserver.app/t"]
assert all(len(o) == len(TMP_NEW) for o in TMP_OLD)


# Play'e özgü ortam: programlar kitaplık klasöründen çalıştığı için `$ORIGIN`'e göre aranan kitaplıklar bulunmaz
# (java -> jdk/lib/libjli.so); arama yolu paket klasöründen verilir.
PLAY_ENV = {
    "openjdk": {"LD_LIBRARY_PATH": "${PKG}/lib:${PKG}/jdk/lib:${PKG}/jdk/lib/server"},
}


def fetch(url):
    with urllib.request.urlopen(url, timeout=300) as r:
        return r.read()


def link_target(name, data, files):
    """Zip'te bağlantı olarak saklanmış girdinin hedefi (paket içinde var olmalı); bağlantı değilse None."""
    if not 0 < len(data) < 256 or data[:4] == b"\x7fELF" or not LINK_TEXT.match(data):
        return None
    target = posixpath.normpath(posixpath.join(posixpath.dirname(name), data.decode()))
    return target if target in files and target != name else None


def module_files(cid, z):
    """Paketteki yol -> modüldeki dosya adı ve dosya adı -> içerik."""
    names = sorted(n for n in z.namelist() if not n.endswith("/"))
    content = {n: z.read(n) for n in names}
    links = {n: t for n in names if (t := link_target(n, content[n], content)) is not None}
    index, blobs = {}, {}
    for n in names:
        if n in links:
            continue
        lib = f"lib{cid}_{len(blobs)}.so"
        data = content[n]
        for old in TMP_OLD:
            data = data.replace(old, TMP_NEW)
        blobs[lib] = data
        index[n] = lib
    for n in links:
        seen, t = {n}, links[n]
        while t in links and t not in seen:  # bağlantı zinciri
            seen.add(t)
            t = links[t]
        if t in index:
            index[n] = index[t]
    return index, blobs


def main(ids):
    packages = {p["id"]: p for p in json.loads(fetch(URL))["packages"]}
    catalog = []
    for cid in ids or list(packages):
        pkg = packages.get(cid) or sys.exit(f"Depoda '{cid}' yok: {URL}")
        shutil.rmtree(os.path.join(ROOT, cid), ignore_errors=True)
        for abi in ABIS:
            build = pkg["builds"].get(abi) or sys.exit(f"{cid}: {abi} derlemesi yok")
            data = fetch(build["url"])
            if hashlib.sha256(data).hexdigest().lower() != build["sha256"].lower():
                sys.exit(f"{cid} {abi}: sha256 uyuşmuyor")
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                index, blobs = module_files(cid, z)
            missing = [e for e in pkg["executables"] if e not in index]
            if missing:
                sys.exit(f"{cid} {abi}: çalıştırılabilir dosyalar pakette yok: {missing}")
            out = os.path.join(ROOT, cid, "jniLibs", abi)
            os.makedirs(out)
            for lib, blob in blobs.items():
                with open(os.path.join(out, lib), "wb") as f:
                    f.write(blob)
            with open(os.path.join(out, f"lib{cid}_files.so"), "w") as f:
                json.dump(index, f, separators=(",", ":"), sort_keys=True)
            size = sum(len(b) for b in blobs.values())
            print(f"{cid} {pkg['version']} {abi}: {len(index)} yol, {len(blobs)} dosya, {size / 2**20:.0f} MB")
        if cid in PLAY_ENV:
            pkg = {**pkg, "env": {**pkg.get("env", {}), **PLAY_ENV[cid]}}
        catalog.append(pkg)
    # Yalnızca bazı bileşenler hazırlandıysa kataloktaki diğerleri (modülleri duruyorsa) korunur.
    path = os.path.join(ROOT, "catalog", "play-components.json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    done = {p["id"] for p in catalog}
    if os.path.isfile(path):
        with open(path) as f:
            old = json.load(f)["packages"]
        catalog = [p for p in old if p["id"] not in done and os.path.isdir(os.path.join(ROOT, p["id"]))] + catalog
    with open(path, "w") as f:
        json.dump({"packages": sorted(catalog, key=lambda p: p["id"])}, f, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main(sys.argv[1:])
