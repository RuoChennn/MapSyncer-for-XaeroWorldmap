#!/usr/bin/env bash
# ==========================================
# MapPackager - Xaero Map Packager Script
# Usage: ./package.sh [path/to/server_map_cache]
#        or place in server root and run
# ==========================================

set -euo pipefail

cd "$(dirname "$0")"

# Find Java
JAVA="java"
if ! command -v java &>/dev/null; then
    for d in Oracle-jdk-21 jdk-21 jdk java; do
        if [ -x "$d/bin/java" ]; then
            JAVA="$d/bin/java"
            break
        fi
    done
fi

# Locate JAR
JAR=$(ls mapsyncer-packager-*.jar 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
    echo "[MapPackager] mapsyncer-packager-*.jar not found"
    exit 1
fi

# Determine cache dir
if [ -n "${1:-}" ]; then
    CACHE_DIR="$1"
elif [ -d "server_map_cache" ]; then
    CACHE_DIR="server_map_cache"
else
    echo "[MapPackager] server_map_cache not found"
    echo "Usage: ./package.sh [path/to/server_map_cache]"
    exit 1
fi

if [ ! -d "$CACHE_DIR" ]; then
    echo "[MapPackager] Cache dir not found: $CACHE_DIR"
    exit 1
fi

# Auto-detect world dir
WORLD_DIR=""
if [ -f "world/xaeromap.txt" ]; then
    WORLD_DIR="world"
elif [ -f "world1/xaeromap.txt" ]; then
    WORLD_DIR="world1"
fi

# Date
DATE_PART=$(date +%Y-%m-%d)
TIME_PART=$(date +%H%M%S)

# Output always in script dir
OUTPUT="server_map_cache_${DATE_PART}.zip"
if [ -f "$OUTPUT" ]; then
    OUTPUT="server_map_cache_${DATE_PART}_${TIME_PART}.zip"
fi

echo ""
echo "========================================"
echo "  MapPackager - Xaero Map Packager"
echo "========================================"
echo "  Cache: $CACHE_DIR"
echo "  Output: $OUTPUT"
[ -n "$WORLD_DIR" ] && echo "  World: $WORLD_DIR"
echo "========================================"
echo ""

if [ -n "$WORLD_DIR" ]; then
    "$JAVA" -jar "$JAR" -c "$CACHE_DIR" -o "$OUTPUT" -d "$WORLD_DIR"
else
    "$JAVA" -jar "$JAR" -c "$CACHE_DIR" -o "$OUTPUT"
fi

if [ -f "$OUTPUT" ]; then
    SIZE=$(stat -f%z "$OUTPUT" 2>/dev/null || stat -c%s "$OUTPUT" 2>/dev/null || echo "?")
    echo "[MapPackager] Done: $(pwd)/$OUTPUT ($SIZE bytes)"
else
    echo "[MapPackager] WARNING: Output not found"
fi
echo ""
