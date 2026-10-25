#!/usr/bin/env bash
# Termux deposundaki bir derlemeyi DroidServer bileşen paketine (zip + sha256) dönüştürür: katalogdaki çalışma
# ortamları (Node.js, Python, OpenJDK) ve servisler (Redis). PostgreSQL ve cloudflared'ın kendi betikleri var.
#
# Neden Termux: Android'in bionic libc'sine göre derlenmiş, Android'e uyarlanmış (libandroid-*) ikililer. Betik paketi
# ve bağımlılıklarını indirir, uygulamanın beklediği düzene koyar; ikililer bu makinede çalıştırılmaz.
#
# Kullanım: tools/package-termux.sh <redis|nodejs|python|openjdk> [çıktı-klasörü]   (varsayılan: dist/)
# Ortam:    ARCH=aarch64|arm|x86_64 (varsayılan aarch64)   REPO=<termux deposu>
# Gerekenler: bash, curl, ar (binutils), tar + xz, zip, sha256sum, awk, python3, readelf. İsteğe bağlı: patchelf.
set -euo pipefail

PROFILE="${1:?profil gerekli: redis, nodejs, python, openjdk}"
ARCH="${ARCH:-aarch64}"
REPO="${REPO:-https://packages.termux.dev/apt/termux-main}"
OUT="$(mkdir -p "${2:-dist}" && cd "${2:-dist}" && pwd)"
TOOLS="$(cd "$(dirname "$0")" && pwd)"
case "$ARCH" in
  aarch64) ABI=arm64-v8a ;;
  arm) ABI=armeabi-v7a ;;
  x86_64) ABI=x86_64 ;;
  *) echo "Desteklenmeyen ARCH: $ARCH" >&2; exit 1 ;;
esac
PREFIX="data/data/com.termux/files/usr"

# Profil: kök Termux paketi, atlanacak bağımlılıklar (ses, grafik: sunucuda gereksiz) ve paket kimliği.
case "$PROFILE" in
  redis)   ROOT_PKG=redis;       ID=redis;   EXCLUDE="";                EXTRA="" ;;
  # npm Termux'ta ayrı paket (nodejs-lts yalnızca önerir); bin/npm ve npx onu çalıştırır.
  nodejs)  ROOT_PKG=nodejs-lts;  ID=nodejs;  EXCLUDE="";                EXTRA="ca-certificates npm" ;;
  # pip de Termux'ta ayrı paket (python yalnızca önerir); bin/pip onu çalıştırır.
  python)  ROOT_PKG=python;      ID=python;  EXCLUDE="ncurses-ui-libs"; EXTRA="ca-certificates python-pip" ;;
  # libandroid-spawn libc++'ı bildirmeden kullanır.
  openjdk) ROOT_PKG=openjdk-21;  ID=openjdk; EXCLUDE="alsa-plugins alsa-lib pulseaudio libjpeg-turbo littlecms"; EXTRA="libc++" ;;
  *) echo "Bilinmeyen profil: $PROFILE" >&2; exit 1 ;;
esac

work="$(mktemp -d)"
[ -n "${KEEP:-}" ] && echo "Çalışma klasörü: $work" || trap 'rm -rf "$work"' EXIT
mkdir -p "$work/debs" "$work/root" "$work/pkg"

echo "Paket listesi indiriliyor ($ARCH)…"
curl -fsSL "$REPO/dists/stable/main/binary-$ARCH/Packages" -o "$work/Packages"
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

[[ -n "$(field "$ROOT_PKG" 3)" ]] || { echo "$ROOT_PKG bu mimaride ($ARCH) yok; atlanıyor." >&2; exit 3; }

declare -A seen=()
for x in $EXCLUDE; do seen[$x]=skip; done
queue=("$ROOT_PKG" $EXTRA)
while ((${#queue[@]})); do
  pkg="${queue[0]}"; queue=("${queue[@]:1}")
  [[ -n "${seen[$pkg]:-}" ]] && continue
  [[ -z "$(field "$pkg" 3)" ]] && { echo "Depoda yok: $pkg" >&2; exit 1; }
  seen[$pkg]=1
  deps="$(field "$pkg" 2)"
  [[ -z "$deps" ]] && continue
  while IFS= read -r dep; do
    # Seçenekli bağımlılık (npm: "nodejs | nodejs-lts"): seçeneklerden biri zaten alındıysa eklenmez, yoksa ilki.
    first=""; have=""
    while IFS= read -r alt; do
      alt="${alt%%(*}"; alt="$(echo "$alt" | tr -d ' ')"
      [[ -z "$alt" ]] && continue
      [[ -z "$first" ]] && first="$alt"
      [[ "${seen[$alt]:-}" == 1 || " ${queue[*]} " == *" $alt "* ]] && have=1
    done < <(echo "$dep" | tr '|' '\n')
    [[ -n "$first" && -z "$have" && -z "${seen[$first]:-}" ]] && queue+=("$first")
  done < <(echo "$deps" | tr ',' '\n')
done

# Sürüm: dosya adında - ve : olmasın (make-manifest.sh "<id>-<sürüm>-<abi>.zip" bekler).
RAW_VERSION="$(field "$ROOT_PKG" 5)"
VERSION="$(echo "${RAW_VERSION#*:}" | tr '-' '.')"
echo "$ROOT_PKG $RAW_VERSION; paketler: $(for k in "${!seen[@]}"; do [[ ${seen[$k]} == 1 ]] && printf '%s ' "$k"; done)"

for pkg in "${!seen[@]}"; do
  [[ "${seen[$pkg]}" == 1 ]] || continue
  file="$(field "$pkg" 3)"; sum="$(field "$pkg" 4)"
  deb="$work/debs/$(basename "$file")"
  curl -fsSL "$REPO/$file" -o "$deb"
  echo "$sum  $deb" | sha256sum -c --quiet - || { echo "sha256 uyuşmuyor: $pkg" >&2; exit 1; }
  (cd "$work/debs" && ar x "$deb" && tar -xf data.tar.* -C "$work/root" && rm -f data.tar.* control.tar.* debian-binary)
done

usr="$work/root/$PREFIX"
pkg="$work/pkg"
mkdir -p "$pkg/bin" "$pkg/lib"

# Sembolik bağlantıları (Termux önekine mutlak olanlar dahil) hedeflerinin kopyasına çevirir.
resolve_links() {
  local dir="$1" changed=1
  while ((changed)); do
    changed=0
    while IFS= read -r l; do
      local t; t="$(readlink "$l")"
      if [[ "$t" == /* ]]; then t="$work/root$t"; else t="$(dirname "$l")/$t"; fi
      rm "$l"
      if [ -d "$t" ]; then cp -a "$t" "$l"; changed=1; elif [ -f "$t" ]; then cp -a "$t" "$l"; changed=1; fi
    done < <(find "$dir" -type l)
  done
}

# Komut sarmalayıcısı: Termux betiklerinin #! satırı telefonda olmayan yolu gösterir; /system/bin/sh ile çalıştırılır.
# Node araçları (npm …) --preserve-symlinks ile bağlantıların kendi yolundan çözülür: Play sürümünde paket klasörü
# kitaplık klasöründeki dosyalara giden bağlantılardan oluşur, gerçek yol oradaki düz listeyi gösterir
# (bkz. tools/play-components.py). GitHub sürümünde dosyalar gerçek olduğundan etkisizdir.
wrapper() { # ad, komut (paket köküne göre; "$D" bin klasörü)
  printf '#!/system/bin/sh\nD="$(cd "$(dirname "$0")" && pwd)"\nexec %s "$@"\n' "$2" > "$pkg/bin/$1"
  chmod +x "$pkg/bin/$1"
}

case "$PROFILE" in
  redis)
    cp -a "$usr/bin/redis-server" "$usr/bin/redis-cli" "$pkg/bin/"
    ;;
  nodejs)
    cp -a "$usr/bin/node" "$pkg/bin/"
    cp -a "$usr/lib/node_modules" "$pkg/lib/"
    wrapper npm '"$D/node" --preserve-symlinks --preserve-symlinks-main "$D/../lib/node_modules/npm/bin/npm-cli.js"'
    wrapper npx '"$D/node" --preserve-symlinks --preserve-symlinks-main "$D/../lib/node_modules/npm/bin/npx-cli.js"'
    [ -d "$usr/lib/node_modules/corepack" ] && wrapper corepack '"$D/node" --preserve-symlinks --preserve-symlinks-main "$D/../lib/node_modules/corepack/dist/corepack.js"'
    mkdir -p "$pkg/etc/tls" && cp -a "$usr/etc/tls/cert.pem" "$pkg/etc/tls/"
    ;;
  python)
    pyver="$(ls "$usr/lib" | sed -n 's/^python\(3\.[0-9]*\)$/\1/p' | head -1)"
    [ -n "$pyver" ] || { echo "Python standart kütüphanesi bulunamadı" >&2; exit 1; }
    cp -a "$usr/bin/python$pyver" "$pkg/bin/python3"
    cp -a "$usr/lib/python$pyver" "$pkg/lib/"
    find "$pkg/lib/python$pyver" -name '__pycache__' -type d -prune -exec rm -rf {} +
    rm -rf "$pkg/lib/python$pyver/test" "$pkg/lib/python$pyver/idlelib" "$pkg/lib/python$pyver/tkinter" "$pkg/lib/python$pyver/turtledemo"
    wrapper python '"$D/python3"'
    wrapper pip '"$D/python3" -m pip'
    wrapper pip3 '"$D/python3" -m pip'
    mkdir -p "$pkg/etc/tls" && cp -a "$usr/etc/tls/cert.pem" "$pkg/etc/tls/"
    ;;
  openjdk)
    jdk="$(ls -d "$usr"/lib/jvm/java-*-openjdk | head -1)"
    cp -a "$jdk" "$pkg/jdk"
    rm -rf "$pkg/jdk/demo" "$pkg/jdk/man" "$pkg/jdk/jmods"
    ;;
esac

# Paylaşılan kütüphaneler (bağlantılarıyla); paket içi ağaçlar (lib/python3.x, node_modules, jdk) yukarıda kopyalandı.
find "$usr/lib" -maxdepth 1 \( -name '*.so' -o -name '*.so.*' \) -exec cp -a {} "$pkg/lib/" \;
resolve_links "$pkg"

# Termux'un geçici klasörü telefonda yok; gömülü yol uygulamanın klasörüne çevrilir.
find "$pkg" -type f -print0 | xargs -0 python3 -I "$TOOLS/patch-termux-paths.py"

if command -v patchelf >/dev/null; then
  for f in "$pkg"/bin/*; do
    [ -f "$f" ] && head -c 4 "$f" | grep -q 'ELF' && patchelf --set-rpath '$ORIGIN/../lib' "$f"
  done
  find "$pkg/lib" -maxdepth 1 -type f -name '*.so*' -exec patchelf --set-rpath '$ORIGIN' {} \;
else
  echo "Uyarı: patchelf yok; kütüphaneler LD_LIBRARY_PATH=\${PKG}/lib ile bulunacak." >&2
fi

# Kütüphaneler SONAME adıyla tek kopya (bağlayıcı yalnızca o adı arar).
for f in "$pkg"/lib/*.so*; do
  [ -f "$f" ] || continue
  soname="$(readelf -d "$f" 2>/dev/null | sed -n 's/.*Library soname: \[\(.*\)\]/\1/p')"
  if [ -n "$soname" ] && [ "$soname" != "$(basename "$f")" ]; then
    if [ -f "$pkg/lib/$soname" ] && cmp -s "$f" "$pkg/lib/$soname"; then rm -f "$f"; else mv -f "$f" "$pkg/lib/$soname"; fi
  fi
done
# Geliştirme bağlantılarından kalan aynı kütüphane kopyaları (libfoo.so = libfoo.so.1) atılır; SONAME'i kendi adı
# olan veya SONAME'i olmayan (bağlayıcı dosya adını arar, örn. Termux'un libsqlite3.so'su) kütüphaneye dokunulmaz.
for f in "$pkg"/lib/*.so; do
  [ -f "$f" ] || continue
  own="$(readelf -d "$f" 2>/dev/null | sed -n 's/.*Library soname: \[\(.*\)\]/\1/p')"
  { [ -z "$own" ] || [ "$own" = "$(basename "$f")" ]; } && continue
  for other in "$f".*; do [ -f "$other" ] && cmp -s "$f" "$other" && { rm -f "$f"; break; }; done
done
[ -z "$(find "$pkg" -type l)" ] || { echo "Sembolik bağlantı kaldı" >&2; exit 1; }

# bin/ ve lib/ altındaki her ikilinin kütüphaneleri pakette veya Android'de olmalı (JDK kendi içinde $ORIGIN ile bulur).
missing=0
while read -r f; do
  for need in $(readelf -d "$f" 2>/dev/null | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p'); do
    case "$need" in libc.so|libm.so|libdl.so|liblog.so|libandroid.so|libz.so|libEGL.so|libGLESv2.so|libjnigraphics.so|libOpenSLES.so|libmediandk.so) continue ;; esac
    [ -f "$pkg/lib/$need" ] || [ -n "$(find "$pkg" -name "$need" -print -quit)" ] || { echo "Eksik kütüphane: $need ($(basename "$f"))" >&2; missing=1; }
  done
done < <(find "$pkg/bin" "$pkg/lib" -maxdepth 1 -type f)
[ "$missing" = 0 ] || exit 1

zip_name="$ID-$VERSION-$ABI.zip"
rm -f "$OUT/$zip_name"
(cd "$pkg" && zip -qr -X "$OUT/$zip_name" .)
sha="$(sha256sum "$OUT/$zip_name" | cut -d' ' -f1)"
echo "$sha  $zip_name" > "$OUT/$zip_name.sha256"
echo
echo "Hazır: $OUT/$zip_name ($(du -h "$OUT/$zip_name" | cut -f1)); sha256: $sha"
