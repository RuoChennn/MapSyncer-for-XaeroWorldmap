#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

echo "============================================"
echo "  Building: MapPackager Tool"
echo "============================================"
echo ""

./gradlew :libs:core:mapPackagerDist

echo ""
echo "Collecting output..."
mkdir -p output
cp libs/core/build/dist/mapsyncer-packager-*.zip output/

echo ""
echo "============================================"
echo "  Build Complete - output/"
echo "============================================"
ls -1 output/mapsyncer-packager-*.zip 2>/dev/null
echo "============================================"
