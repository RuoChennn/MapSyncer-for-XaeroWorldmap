#!/usr/bin/env bash
# ==========================================
# MapPackager - Xaero Map Packager Script
# Usage: ./package.sh [cache_dir] [server_address]
#        或在下方配置 SERVER_ADDRESS / OUTPUT_NAME 后直接运行
# ==========================================

# ========== 自定义配置（修改下面两行即可）==========
SERVER_ADDRESS=""
OUTPUT_NAME=""
# SERVER_ADDRESS  服务器地址，如 play.example.com:25565（留空则使用占位名 Server）
# OUTPUT_NAME       输出 zip 文件名，如 my_server_map.zip（留空则按日期自动生成）
# ==================================================

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

# Cache dir: arg1 overrides default
if [ -n "${1:-}" ]; then
    CACHE_DIR="$1"
elif [ -d "server_map_cache" ]; then
    CACHE_DIR="server_map_cache"
else
    echo "[MapPackager] server_map_cache not found"
    echo "Usage: ./package.sh [path/to/server_map_cache] [server_address]"
    exit 1
fi

# Server address: arg2 overrides header config
if [ -n "${2:-}" ]; then
    SERVER_ADDRESS="$2"
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

# Output file name
if [ -n "$OUTPUT_NAME" ]; then
    OUTPUT="$OUTPUT_NAME"
else
    DATE_PART=$(date +%Y-%m-%d)
    TIME_PART=$(date +%H%M%S)
    OUTPUT="server_map_cache_${DATE_PART}.zip"
    if [ -f "$OUTPUT" ]; then
        OUTPUT="server_map_cache_${DATE_PART}_${TIME_PART}.zip"
    fi
fi

echo ""
echo "========================================"
echo "  MapPackager - Xaero Map Packager"
echo "========================================"
echo "  Cache: $CACHE_DIR"
echo "  Output: $OUTPUT"
[ -n "$WORLD_DIR" ] && echo "  World: $WORLD_DIR"
if [ -n "$SERVER_ADDRESS" ]; then
    echo "  Server: $SERVER_ADDRESS"
else
    echo "  Server: (placeholder Server)"
fi
echo "========================================"
echo ""

EXTRA_ARGS=()
[ -n "$SERVER_ADDRESS" ] && EXTRA_ARGS=(-a "$SERVER_ADDRESS")

if [ -n "$WORLD_DIR" ]; then
    "$JAVA" -jar "$JAR" -c "$CACHE_DIR" -o "$OUTPUT" -d "$WORLD_DIR" "${EXTRA_ARGS[@]}"
else
    "$JAVA" -jar "$JAR" -c "$CACHE_DIR" -o "$OUTPUT" "${EXTRA_ARGS[@]}"
fi

if [ -f "$OUTPUT" ]; then
    SIZE=$(stat -f%z "$OUTPUT" 2>/dev/null || stat -c%s "$OUTPUT" 2>/dev/null || echo "?")
    echo "[MapPackager] Done: $(pwd)/$OUTPUT ($SIZE bytes)"
else
    echo "[MapPackager] WARNING: Output not found"
fi
echo ""
