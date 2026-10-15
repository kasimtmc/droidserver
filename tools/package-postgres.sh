#!/usr/bin/env bash
# Termux deposundaki PostgreSQL derlemesini DroidServer bileşen paketine (zip + sha256) dönüştürür.
#
# Neden Termux: Android'de SysV paylaşımlı bellek yok; Termux, PostgreSQL'i libandroid-shmem ile ve
# Android'in bionic libc'sine göre derler. Bu betik o derlemeyi ve bağımlılıklarını indirip
# uygulamanın beklediği düzene (bin/, lib/, share/) koyar. Binary'ler bu makinede çalıştırılmaz.
#
# Kullanım: tools/package-postgres.sh [çıktı-klasörü]       (varsayılan: dist/)
# Ortam:    ARCH=aarch64|arm|x86_64 (varsayılan aarch64)   REPO=<termux deposu>
# Gerekenler: bash, curl, ar (binutils), tar + xz, zip, sha256sum, awk, python3, readelf. İsteğe bağlı: patchelf
# (yoksa kütüphaneler yalnızca LD_LIBRARY_PATH=${PKG}/lib ile bulunur; bileşen tanımı bunu zaten verir).
set -euo pipefail

ARCH="${ARCH:-aarch64}"
REPO="${REPO:-https://packages.termux.dev/apt/termux-main}"
OUT="$(mkdir -p "${1:-dist}" && cd "${1:-dist}" && pwd)"
TOOLS="$(cd "$(dirname "$0")" && pwd)"
case "$ARCH" in
  aarch64) ABI=arm64-v8a ;;
  arm) ABI=armeabi-v7a ;;
  x86_64) ABI=x86_64 ;;
  *) echo "Desteklenmeyen ARCH: $ARCH" >&2; exit 1 ;;
esac
# Gerçek Termux öneki: binary'ler buna göre derlenmiştir, paket içinde göreli düzen korunur.
PREFIX="data/data/com.termux/files/usr"
BINARIES="postgres initdb pg_ctl psql pg_dump pg_restore pg_isready"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/debs" "$work/root" "$work/pkg"

echo "Paket listesi indiriliyor ($ARCH)…"
curl -fsSL "$REPO/dists/stable/main/binary-$ARCH/Packages" -o "$work/Packages"

# Her paket için tek satır: ad <TAB> bağımlılıklar <TAB> dosya <TAB> sha256 <TAB> sürüm
awk -v RS= -F'\n' '{
  name = deps = file = sum = ver = ""
  for (i = 1; i <= NF; i++) {
    if ($i ~ /^Package: /) name = substr($i, 10)
    else if ($i ~ /^Depends: /) deps = substr($i, 10)
    else if ($i ~ /^Filename: /) file = substr($i, 11)
    else if ($i ~ /^SHA256: /) sum = substr($i, 9)
    else if ($i ~ /^Version: /) ver = substr($i, 10)
  }
  if (name != "") printf "%s\t%s\t%s\t%s\t%s\n", name, deps, file, sum, ver
}' "$work/Packages" > "$work/index.tsv"

field() { awk -F'\t' -v n="$1" -v c="$2" '$1 == n { print $c; exit }' "$work/index.tsv"; }

# Bağımlılıkları genişlikte arama ile çöz. Sürüm kısıtları atılır, alternatiflerden ilki alınır.
declare -A seen=()
queue=(postgresql)
while ((${#queue[@]})); do
  pkg="${queue[0]}"; queue=("${queue[@]:1}")
  [[ -n "${seen[$pkg]:-}" ]] && continue
  [[ -z "$(field "$pkg" 3)" ]] && { echo "Depoda yok: $pkg" >&2; exit 1; }
  seen[$pkg]=1
  deps="$(field "$pkg" 2)"
  [[ -z "$deps" ]] && continue
  while IFS= read -r dep; do
    dep="${dep%%|*}"; dep="${dep%%(*}"; dep="$(echo "$dep" | tr -d ' ')"
    [[ -n "$dep" && -z "${seen[$dep]:-}" ]] && queue+=("$dep")
  done < <(echo "$deps" | tr ',' '\n')
done

VERSION="$(field postgresql 5)"
echo "PostgreSQL $VERSION; paketler: ${!seen[*]}"

for pkg in "${!seen[@]}"; do
  file="$(field "$pkg" 3)"; sum="$(field "$pkg" 4)"
  deb="$work/debs/$(basename "$file")"
  curl -fsSL "$REPO/$file" -o "$deb"
  echo "$sum  $deb" | sha256sum -c --quiet - || { echo "sha256 uyuşmuyor: $pkg" >&2; exit 1; }
  (cd "$work/debs" && ar x "$deb" && tar -xf data.tar.* -C "$work/root" && rm -f data.tar.* control.tar.* debian-binary)
done

usr="$work/root/$PREFIX"
mkdir -p "$work/pkg/bin" "$work/pkg/lib" "$work/pkg/share"
for b in $BINARIES; do cp -a "$usr/bin/$b" "$work/pkg/bin/"; done
# Paylaşılan kütüphaneler (bağlantılarıyla) ve PostgreSQL modülleri (plpgsql vb.)
find "$usr/lib" -maxdepth 1 \( -name '*.so' -o -name '*.so.*' \) -exec cp -a {} "$work/pkg/lib/" \;
cp -a "$usr/lib/postgresql" "$work/pkg/lib/"
cp -a "$usr/share/postgresql" "$work/pkg/share/"

# Termux'un geçici klasörü telefonda yok; gömülü yol uygulamanın klasörüne çevrilir (ayrıntı betikte).
find "$work/pkg" -type f -print0 | xargs -0 python3 -I "$TOOLS/patch-termux-paths.py"

# PostgreSQL yer değiştirebilir: share/ ve lib/postgresql/ yollarını bin/ konumuna göre bulur.
# Kütüphane arama yolu (RUNPATH) Termux önekini gösterir; patchelf varsa pakete göreli yapılır.
if command -v patchelf >/dev/null; then
  for f in "$work"/pkg/bin/*; do patchelf --set-rpath '$ORIGIN/../lib' "$f"; done
  find "$work/pkg/lib" -maxdepth 1 -type f -name '*.so*' -exec patchelf --set-rpath '$ORIGIN' {} \;
  find "$work/pkg/lib/postgresql" -type f -name '*.so' -exec patchelf --set-rpath '$ORIGIN/..' {} \;
else
  echo "Uyarı: patchelf yok; kütüphaneler LD_LIBRARY_PATH=\${PKG}/lib ile bulunacak." >&2
fi

# Sembolik bağlantı kalmamalı: zip'e bağlantı olarak girerse Android'deki açıcı (java.util.zip) onu, içinde hedef
# adı yazan düz bir dosyaya çevirir ("libicui18n.so.78 is too small to be an ELF executable").
# Kütüphaneler: gerçek dosya SONAME adını alır (bağlayıcı yalnızca o adı arar), geliştirme bağlantıları atılır.
for f in "$work"/pkg/lib/*.so*; do
  [ -f "$f" ] && [ ! -L "$f" ] || continue
  soname="$(readelf -d "$f" 2>/dev/null | sed -n 's/.*Library soname: \[\(.*\)\]/\1/p')"
  if [ -n "$soname" ] && [ "$soname" != "$(basename "$f")" ]; then
    rm -f "$work/pkg/lib/$soname"
    mv "$f" "$work/pkg/lib/$soname"
  fi
done
find "$work/pkg/lib" -maxdepth 1 -type l -delete
# Diğer bağlantılar (saat dilimi verisi vb.) hedeflerinin kopyasıyla değiştirilir.
find "$work/pkg" -type l | while read -r l; do
  t="$(readlink -f "$l" || true)"
  rm "$l"
  if [ -d "$t" ]; then cp -r "$t" "$l"; elif [ -f "$t" ]; then cp "$t" "$l"; fi
done
[ -z "$(find "$work/pkg" -type l)" ] || { echo "Sembolik bağlantı kaldı" >&2; exit 1; }
# Her çalıştırılabilirin ve modülün kütüphaneleri pakette veya Android'de olmalı.
missing=0
while read -r f; do
  for need in $(readelf -d "$f" 2>/dev/null | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p'); do
    case "$need" in libc.so|libm.so|libdl.so|liblog.so|libandroid.so|libz.so) continue ;; esac
    [ -f "$work/pkg/lib/$need" ] || { echo "Eksik kütüphane: $need ($(basename "$f"))" >&2; missing=1; }
  done
done < <(find "$work/pkg/bin" "$work/pkg/lib" -type f \( -perm -u+x -o -name '*.so*' \))
[ "$missing" = 0 ] || exit 1

zip_name="postgres-$VERSION-$ABI.zip"
rm -f "$OUT/$zip_name"
(cd "$work/pkg" && zip -qr -X "$OUT/$zip_name" .)
sha="$(sha256sum "$OUT/$zip_name" | cut -d' ' -f1)"
echo "$sha  $zip_name" > "$OUT/$zip_name.sha256"

echo
echo "Hazır: $OUT/$zip_name ($(du -h "$OUT/$zip_name" | cut -f1))"
echo "sha256: $sha"
echo "components.json'daki postgres girdisinde (docs/components.example.json) şunları güncelleyin:"
echo "  \"version\": \"$VERSION\", \"builds\": { \"$ABI\": { \"url\": \"<barındırdığınız adres>/$zip_name\", \"sha256\": \"$sha\" } }"
