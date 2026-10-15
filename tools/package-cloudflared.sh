#!/usr/bin/env bash
# Termux deposundaki cloudflared derlemesini DroidServer bileşen paketine (zip + sha256) dönüştürür.
#
# Neden Termux: Cloudflare'in resmi linux-arm64 derlemesi statik Go binary'sidir; Android'de DNS çözümlemesi için
# /etc/resolv.conf arar, o dosya Android'de yoktur. Termux derlemesi bionic libc'ye bağlıdır (cgo), böylece
# GODEBUG=netdns=cgo ile adresleri Android'in kendi çözümleyicisiyle çözer (Özel DNS ve VPN ayarlarına uyar).
# Saf Go çözümleyicisinin okuduğu resolv.conf yolu binary içinde Termux'u gösterir; bu betik onu uygulamanın
# yazdığı dosyaya çevirir (/data/data/dev.droidserver.app/files/dns.conf, uygulama ağın DNS sunucularıyla doldurur).
# Binary'ler bu makinede çalıştırılmaz.
#
# Kullanım: tools/package-cloudflared.sh [çıktı-klasörü]     (varsayılan: dist/)
# Ortam:    ARCH=aarch64|arm|x86_64 (varsayılan aarch64)   REPO=<termux deposu>
# Gerekenler: bash, curl, ar (binutils), tar + xz, zip, sha256sum, awk, python3. İsteğe bağlı: patchelf
set -euo pipefail

ARCH="${ARCH:-aarch64}"
REPO="${REPO:-https://packages.termux.dev/apt/termux-main}"
OUT="$(mkdir -p "${1:-dist}" && cd "${1:-dist}" && pwd)"
case "$ARCH" in
  aarch64) ABI=arm64-v8a ;;
  arm) ABI=armeabi-v7a ;;
  x86_64) ABI=x86_64 ;;
  *) echo "Desteklenmeyen ARCH: $ARCH" >&2; exit 1 ;;
esac
# Termux yolu ve yerine yazılacak yol aynı uzunlukta olmalı (Go dizgelerinin uzunluğu binary'de ayrıca saklanır);
# fazladan baştaki eğik çizgiler yolu değiştirmez.
OLD_RESOLV="/data/data/com.termux/files/usr/etc/resolv.conf"
NEW_RESOLV="/data/data/dev.droidserver.app/files/dns.conf"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "Paket listesi indiriliyor ($ARCH)…"
curl -fsSL "$REPO/dists/stable/main/binary-$ARCH/Packages" -o "$work/Packages"
read -r VERSION FILE SUM < <(awk -v RS= -F'\n' '{
  name = file = sum = ver = ""
  for (i = 1; i <= NF; i++) {
    if ($i ~ /^Package: /) name = substr($i, 10)
    else if ($i ~ /^Filename: /) file = substr($i, 11)
    else if ($i ~ /^SHA256: /) sum = substr($i, 9)
    else if ($i ~ /^Version: /) ver = substr($i, 10)
  }
  if (name == "cloudflared") { print ver, file, sum; exit }
}' "$work/Packages")
[ -n "${FILE:-}" ] || { echo "Depoda cloudflared yok ($ARCH)" >&2; exit 1; }
VERSION="${VERSION%%-*}" # Termux paket revizyonu (-1) atılır
echo "cloudflared $VERSION"

curl -fsSL "$REPO/$FILE" -o "$work/cf.deb"
echo "$SUM  $work/cf.deb" | sha256sum -c --quiet - || { echo "sha256 uyuşmuyor" >&2; exit 1; }
mkdir -p "$work/root" "$work/pkg/bin"
(cd "$work" && ar x cf.deb && tar -xf data.tar.* -C root)
bin="$work/pkg/bin/cloudflared"
cp "$work/root/data/data/com.termux/files/usr/bin/cloudflared" "$bin"

python3 -I - "$bin" "$OLD_RESOLV" "$NEW_RESOLV" <<'EOF'
import sys
path, old, new = sys.argv[1], sys.argv[2].encode(), sys.argv[3].encode()
new = b"/" * (len(old) - len(new)) + new
assert len(new) == len(old), "yeni yol eskisinden uzun"
data = open(path, "rb").read()
n = data.count(old)
if n != 1:
    sys.exit(f"resolv.conf yolu binary'de {n} kez geçiyor (1 bekleniyordu); derleme değişmiş olabilir")
open(path, "wb").write(data.replace(old, new))
print("resolv.conf yolu:", new.decode())
EOF

# Kütüphane arama yolu Termux önekini gösterir; yalnızca sistem kütüphaneleri (libc, libdl, liblog) gerektiği için kaldırılır.
if command -v patchelf >/dev/null; then patchelf --remove-rpath "$bin"; fi
chmod 755 "$bin"

zip_name="cloudflared-$VERSION-$ABI.zip"
rm -f "$OUT/$zip_name"
(cd "$work/pkg" && zip -qry -X "$OUT/$zip_name" .)
sha="$(sha256sum "$OUT/$zip_name" | cut -d' ' -f1)"
echo "$sha  $zip_name" > "$OUT/$zip_name.sha256"
echo "Hazır: $OUT/$zip_name ($(du -h "$OUT/$zip_name" | cut -f1)), sha256: $sha"
