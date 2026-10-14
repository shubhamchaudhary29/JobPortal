#!/usr/bin/env bash
# Build the self-hosted India basemap volume (GEO-4): a Protomaps OpenStreetMap extract plus the fonts and
# sprites its style uses. Output is runtime data for nginx's /tiles/ mount; never commit it.
#
#   frontend/scripts/build-tiles.sh [--out DIR] [--maxzoom N] [--source URL] [--assets-ref SHA]
#
#   --out DIR         volume directory (default ./tiles)
#   --maxzoom N       highest zoom to extract (default 11; MapLibre over-zooms beyond it)
#   --source URL      a Protomaps daily build, e.g. https://build.protomaps.com/20261014.pmtiles
#                     (default: the newest build listed at https://build-metadata.protomaps.dev/builds.json,
#                     the list behind https://maps.protomaps.com/builds)
#   --assets-ref SHA  protomaps/basemaps-assets commit for fonts and sprites (default pinned below)
#
# The swap is atomic: everything is staged in a temp dir inside --out and manifest.json is replaced last, so a
# reader never sees a manifest that points at a missing file. The previous .pmtiles is kept for clients that
# cached the old manifest; older ones are removed. No container restart is needed.
set -euo pipefail

OUT=./tiles
MAXZOOM=11
SOURCE=
# protomaps/basemaps-assets main as of 2025-10-31; sprites/v4 is what @protomaps/basemaps v5 expects.
ASSETS_REF=028c18f713baecad011301ff7a69acc39bcc2ae7
BBOX=66.0,5.0,98.5,37.5
BBOX_JSON="[66.0, 5.0, 98.5, 37.5]"
FLAVOR=light
# Every text-font stack the @protomaps/basemaps v5 light style uses, including the Devanagari one nested inside
# label "format" expressions (checked by baseStyle.test.js).
FONTSTACKS=("Noto Sans Regular" "Noto Sans Medium" "Noto Sans Italic" "Noto Sans Devanagari Regular v1")
BUDGET_BYTES=$((600 * 1024 * 1024))
BUILDS_JSON=https://build-metadata.protomaps.dev/builds.json
BUILDS_BASE=https://build.protomaps.com/
ATTRIBUTION="© OpenStreetMap contributors · Protomaps · GeoNames"

usage() { sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
    case "$1" in
        --out) OUT=${2:?--out needs a directory}; shift 2 ;;
        --maxzoom) MAXZOOM=${2:?--maxzoom needs a number}; shift 2 ;;
        --source) SOURCE=${2:?--source needs a URL}; shift 2 ;;
        --assets-ref) ASSETS_REF=${2:?--assets-ref needs a commit}; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

case "$MAXZOOM" in ''|*[!0-9]*) echo "--maxzoom must be a number" >&2; exit 2 ;; esac
if [ "$MAXZOOM" -gt 15 ]; then echo "--maxzoom must be 0-15" >&2; exit 2; fi
case "$ASSETS_REF" in *[!0-9a-f]*|'') echo "--assets-ref must be a hex commit id" >&2; exit 2 ;; esac

if ! command -v pmtiles >/dev/null 2>&1; then
    echo "pmtiles CLI not found. Install it from https://github.com/protomaps/go-pmtiles/releases" >&2
    echo "(put the 'pmtiles' binary on PATH, then check with: pmtiles version)" >&2
    exit 3
fi
for tool in curl tar; do
    command -v "$tool" >/dev/null 2>&1 || { echo "$tool is required" >&2; exit 3; }
done

if [ -z "$SOURCE" ]; then
    echo "Finding the newest Protomaps build ..."
    newest=$(curl -fsSL --max-time 60 "$BUILDS_JSON" | grep -oE '"key":"[0-9]{8}\.pmtiles"' \
        | sed -E 's/"key":"([^"]+)"/\1/' | sort | tail -n 1)
    [ -n "$newest" ] || { echo "Could not read $BUILDS_JSON; pass --source explicitly" >&2; exit 4; }
    SOURCE="$BUILDS_BASE$newest"
fi
build_file=${SOURCE##*/}
build_date=$(printf '%s' "$build_file" | grep -oE '^[0-9]{8}' || date -u +%Y%m%d)
TARGET="india-${build_date}-z${MAXZOOM}.pmtiles"
ASSETS_VERSION=${ASSETS_REF:0:12}
ASSETS_PATH="assets/$ASSETS_VERSION"

mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
STAGE=$(mktemp -d "$OUT/.build.XXXXXX")
trap 'rm -rf "$STAGE"' EXIT

echo "Extracting India (bbox $BBOX, maxzoom $MAXZOOM) from $SOURCE ..."
started=$(date +%s)
pmtiles extract "$SOURCE" "$STAGE/$TARGET" --bbox="$BBOX" --maxzoom="$MAXZOOM"
echo "Extract finished in $(( $(date +%s) - started ))s"
pmtiles show "$STAGE/$TARGET"

size=$(wc -c < "$STAGE/$TARGET" | tr -d ' ')
if [ "$size" -gt "$BUDGET_BYTES" ]; then
    echo "WARNING: $TARGET is $((size / 1024 / 1024)) MB, over the 600 MB budget; rerun with --maxzoom $((MAXZOOM - 1))" >&2
fi

assets_complete() {
    for stack in "${FONTSTACKS[@]}"; do [ -d "$OUT/$ASSETS_PATH/fonts/$stack" ] || return 1; done
    for sprite in "$FLAVOR.json" "$FLAVOR.png" "$FLAVOR@2x.json" "$FLAVOR@2x.png"; do
        [ -f "$OUT/$ASSETS_PATH/sprites/$sprite" ] || return 1
    done
}

if ! assets_complete; then
    echo "Fetching fonts and sprites (basemaps-assets $ASSETS_VERSION) ..."
    curl -fsSL --max-time 300 -o "$STAGE/assets.tar.gz" \
        "https://codeload.github.com/protomaps/basemaps-assets/tar.gz/$ASSETS_REF"
    root="basemaps-assets-$ASSETS_REF"
    members=()
    for stack in "${FONTSTACKS[@]}"; do members+=("$root/fonts/$stack"); done
    for sprite in "$FLAVOR.json" "$FLAVOR.png" "$FLAVOR@2x.json" "$FLAVOR@2x.png"; do
        members+=("$root/sprites/v4/$sprite")
    done
    mkdir -p "$STAGE/unpacked"
    tar -xzf "$STAGE/assets.tar.gz" -C "$STAGE/unpacked" "${members[@]}"
    mkdir -p "$STAGE/$ASSETS_PATH/fonts" "$STAGE/$ASSETS_PATH/sprites"
    for stack in "${FONTSTACKS[@]}"; do
        mv "$STAGE/unpacked/$root/fonts/$stack" "$STAGE/$ASSETS_PATH/fonts/$stack"
    done
    mv "$STAGE/unpacked/$root/sprites/v4/"* "$STAGE/$ASSETS_PATH/sprites/"
    # Merge into an existing (incomplete) assets dir: files are content-addressed by the commit, so adding the
    # missing ones never changes what cached clients already fetched.
    mkdir -p "$OUT/$ASSETS_PATH/fonts" "$OUT/$ASSETS_PATH/sprites"
    for stack in "${FONTSTACKS[@]}"; do
        [ -d "$OUT/$ASSETS_PATH/fonts/$stack" ] || mv "$STAGE/$ASSETS_PATH/fonts/$stack" "$OUT/$ASSETS_PATH/fonts/$stack"
    done
    for sprite in "$STAGE/$ASSETS_PATH/sprites/"*; do
        [ -e "$OUT/$ASSETS_PATH/sprites/${sprite##*/}" ] || mv "$sprite" "$OUT/$ASSETS_PATH/sprites/"
    done
else
    echo "Fonts and sprites $ASSETS_VERSION already present"
fi

previous=
if [ -f "$OUT/manifest.json" ]; then
    previous=$(grep -oE '"pmtiles": *"[^"]+"' "$OUT/manifest.json" | sed -E 's/.*"([^"]+)"$/\1/' || true)
fi
mv -f "$STAGE/$TARGET" "$OUT/$TARGET"

cat > "$STAGE/manifest.json" <<JSON
{
  "pmtiles": "$TARGET",
  "maxzoom": $MAXZOOM,
  "bbox": $BBOX_JSON,
  "assets": "$ASSETS_PATH",
  "flavor": "$FLAVOR",
  "sourceBuild": "$build_file",
  "builtAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "attribution": "$ATTRIBUTION"
}
JSON
mv -f "$STAGE/manifest.json" "$OUT/manifest.json"

# Keep the current and the previous archive (cached clients may still read it); remove anything older.
for file in "$OUT"/india-*.pmtiles; do
    [ -e "$file" ] || continue
    name=${file##*/}
    if [ "$name" != "$TARGET" ] && [ "$name" != "$previous" ]; then rm -f "$file"; fi
done

echo "Volume ready in $OUT (manifest -> $TARGET)"
du -sh "$OUT/$TARGET" "$OUT/assets"
