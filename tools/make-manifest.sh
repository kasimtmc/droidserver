#!/usr/bin/env bash
# package-*.sh çıktılarından bileşen manifesti (components.json) üretir.
# docs/components.example.json'daki girdiler şablondur; dist'te zip'i bulunan her paket için sürüm ve ABI başına
# indirme adresi + sha256 doldurulur. Zip'i olmayan paketler manifeste girmez.
#
# Kullanım: tools/make-manifest.sh <dist-klasörü> <indirme-adresi-öneki>
#   örn.    tools/make-manifest.sh dist https://github.com/<sahip>/<depo>/releases/download/components
# Çıktı:    <dist-klasörü>/components.json
# Gerekenler: jq
set -euo pipefail

dist="${1:?dist klasörü gerekli}"
base="${2:?indirme adresi öneki gerekli}"
root="$(cd "$(dirname "$0")/.." && pwd)"
template="$root/docs/components.example.json"

packages='[]'
shopt -s nullglob
for id in $(jq -r '.packages[].id' "$template"); do
  builds='{}'
  version=""
  for zip in "$dist/$id"-*.zip; do
    name="$(basename "$zip")"                 # postgres-18.6-arm64-v8a.zip
    rest="${name#"$id"-}"; rest="${rest%.zip}" # 18.6-arm64-v8a
    ver="${rest%%-*}"; abi="${rest#*-}"
    [ -z "$version" ] || [ "$version" = "$ver" ] || { echo "$id: farklı sürümler: $version, $ver" >&2; exit 1; }
    version="$ver"
    sha="$(cut -d' ' -f1 "$zip.sha256")"
    builds="$(jq --arg abi "$abi" --arg url "$base/$name" --arg sha "$sha" '. + {($abi): {url: $url, sha256: $sha}}' <<<"$builds")"
  done
  [ -n "$version" ] || continue
  packages="$(jq --arg id "$id" --arg v "$version" --argjson b "$builds" --slurpfile t "$template" \
    '. + [$t[0].packages[] | select(.id == $id) | .version = $v | .builds = $b]' <<<"$packages")"
  echo "$id $version: $(jq -r 'keys | join(", ")' <<<"$builds")"
done
[ "$(jq length <<<"$packages")" -gt 0 ] || { echo "$dist içinde paket zip'i yok" >&2; exit 1; }

jq -n --argjson p "$packages" '{packages: $p}' > "$dist/components.json"
echo "Yazıldı: $dist/components.json"
