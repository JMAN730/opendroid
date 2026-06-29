#!/usr/bin/env bash
#
# build.sh — Simple build script for the OpenDroid website.
# Minifies CSS and JS for production without any external tooling (Node, etc.).
#
# Usage:
#   cd website && bash build.sh
#
# What it does:
#   1. Creates a dist/ directory with all website files
#   2. Minifies CSS (strips comments, whitespace)
#   3. Minifies JS (strips comments, whitespace)
#   4. Copies all other files as-is
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DIST_DIR="${SCRIPT_DIR}/dist"

echo "🔨 Building OpenDroid website..."
echo "   Source: ${SCRIPT_DIR}"
echo "   Output: ${DIST_DIR}"
echo ""

# Clean previous build
rm -rf "${DIST_DIR}"
mkdir -p "${DIST_DIR}"

# ── Copy all files first ──
echo "📦 Copying files..."
cp -r "${SCRIPT_DIR}/assets" "${DIST_DIR}/assets"
cp -r "${SCRIPT_DIR}/css" "${DIST_DIR}/css"
cp -r "${SCRIPT_DIR}/js" "${DIST_DIR}/js"
cp "${SCRIPT_DIR}"/*.html "${DIST_DIR}/" 2>/dev/null || true
cp "${SCRIPT_DIR}/sitemap.xml" "${DIST_DIR}/" 2>/dev/null || true
cp "${SCRIPT_DIR}/robots.txt" "${DIST_DIR}/" 2>/dev/null || true

# ── Minify CSS ──
echo "🎨 Minifying CSS..."
for cssfile in "${DIST_DIR}"/css/*.css; do
  if [ -f "$cssfile" ]; then
    original_size=$(stat -c%s "$cssfile" 2>/dev/null || stat -f%z "$cssfile")
    # Remove multi-line comments, collapse whitespace, remove unnecessary spaces
    sed -i \
      -e 's|/\*[^*]*\*\+\([^/][^*]*\*\+\)*/||g' \
      -e 's/^[[:space:]]*//' \
      -e 's/[[:space:]]*$//' \
      -e '/^$/d' \
      "$cssfile"
    # Collapse into fewer lines by joining short lines
    tr '\n' ' ' < "$cssfile" | \
      sed 's/  */ /g' | \
      sed 's/ *{ */{/g' | \
      sed 's/ *} */}\n/g' | \
      sed 's/ *; */;/g' | \
      sed 's/ *: */:/g' | \
      sed 's/ *, */,/g' \
      > "${cssfile}.tmp"
    mv "${cssfile}.tmp" "$cssfile"
    new_size=$(stat -c%s "$cssfile" 2>/dev/null || stat -f%z "$cssfile")
    echo "   $(basename "$cssfile"): ${original_size}B → ${new_size}B"
  fi
done

# ── Minify JS ──
echo "⚡ Minifying JS..."
for jsfile in "${DIST_DIR}"/js/*.js; do
  if [ -f "$jsfile" ]; then
    original_size=$(stat -c%s "$jsfile" 2>/dev/null || stat -f%z "$jsfile")
    # Remove single-line comments (but not URLs with //), strip whitespace
    sed -i \
      -e 's|^[[:space:]]*//.*$||' \
      -e 's/^[[:space:]]*//' \
      -e 's/[[:space:]]*$//' \
      -e '/^$/d' \
      "$jsfile"
    new_size=$(stat -c%s "$jsfile" 2>/dev/null || stat -f%z "$jsfile")
    echo "   $(basename "$jsfile"): ${original_size}B → ${new_size}B"
  fi
done

# ── Summary ──
echo ""
echo "✅ Build complete!"
echo ""
src_size=$(du -sh "${SCRIPT_DIR}" --exclude="${DIST_DIR}" 2>/dev/null | cut -f1 || du -sh "${SCRIPT_DIR}" | cut -f1)
dist_size=$(du -sh "${DIST_DIR}" | cut -f1)
echo "   Source size: ${src_size}"
echo "   Dist size:   ${dist_size}"
echo ""
echo "   Output: ${DIST_DIR}/"
echo ""
echo "   To test locally:  cd dist && python3 -m http.server 8080"
echo "   To deploy:        Push the dist/ contents to the gh-pages branch"
