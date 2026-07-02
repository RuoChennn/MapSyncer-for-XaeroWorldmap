#!/usr/bin/env bash
# MapSyncer 全平台构建（与 build-all.bat 同阶段逻辑）
# 结构: libs/mc-* 锚点 + mc-{版本}/{loader} 胶水；Forge / 部分 Fabric 需隔离 settings
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SETTINGS_FILE="$ROOT/settings.gradle"
SETTINGS_BAK="$ROOT/settings.bak.gradle"
SETTINGS_26="$ROOT/scripts/fastbuild/settings-26.gradle"
SETTINGS_12111="$ROOT/scripts/fastbuild/settings-12111.gradle"
SETTINGS_FORGE="$ROOT/scripts/fastbuild/settings-forge.gradle"
OUTPUT_DIR="$ROOT/output"
GRADLE="$ROOT/gradlew"
COPY_JARS="$ROOT/scripts/fastbuild/copy-release-jars.ps1"

switch_settings() {
    local profile="$1"
    local src
    case "$profile" in
        forge) src="$SETTINGS_FORGE" ;;
        12111) src="$SETTINGS_12111" ;;
        26) src="$SETTINGS_26" ;;
        *) return 0 ;;
    esac
    if [[ ! -f "$SETTINGS_BAK" ]]; then
        cp "$SETTINGS_FILE" "$SETTINGS_BAK"
    fi
    cp "$src" "$SETTINGS_FILE"
}

restore_settings() {
    if [[ -f "$SETTINGS_BAK" ]]; then
        mv -f "$SETTINGS_BAK" "$SETTINGS_FILE"
    fi
}

copy_jars() {
    if command -v pwsh >/dev/null 2>&1; then
        pwsh -NoProfile -File "$COPY_JARS" "$@"
    elif command -v powershell >/dev/null 2>&1; then
        powershell -NoProfile -ExecutionPolicy Bypass -File "$COPY_JARS" "$@"
    else
        mkdir -p "$OUTPUT_DIR"
        for dir in "$@"; do
            [[ -d "$dir" ]] || continue
            find "$dir" -maxdepth 1 -name '*.jar' \
                ! -name '*-slim.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
                -exec cp -f {} "$OUTPUT_DIR/" \;
        done
    fi
}

echo "============================================"
echo "  MapSyncer - Build ALL Platforms"
echo "============================================"
echo

rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

echo "[Phase 1/4] Gradle 9.x (default settings: Fabric + NeoForge)..."
"$GRADLE" \
    :mc-1.20.1:fabric:clean :mc-1.20.1:fabric:build \
    :mc-1.21.1:fabric:clean :mc-1.21.1:fabric:build \
    :mc-1.21.1:neoforge:clean :mc-1.21.1:neoforge:build \
    :mc-1.21.11:neoforge:clean :mc-1.21.11:neoforge:build \
    :mc-26.1:neoforge:clean :mc-26.1:neoforge:build \
    -x test --parallel || echo "  Phase 1 had errors, continuing..."

copy_jars \
    mc-1.20.1/fabric/build/libs \
    mc-1.21.1/fabric/build/libs \
    mc-1.21.1/neoforge/build/libs \
    mc-1.21.11/neoforge/build/libs \
    mc-26.1/neoforge/build/libs

echo
echo "[Phase 2/4] Forge (settings-forge.gradle; requires gradle-8.9 on Windows)..."
if [[ -x "$ROOT/gradle-8.9/bin/gradle" ]]; then
    FORGE_GRADLE="$ROOT/gradle-8.9/bin/gradle"
    switch_settings forge
    "$FORGE_GRADLE" :mc-1.20.1:forge:clean :mc-1.20.1:forge:build -x test --no-daemon || true
    copy_jars mc-1.20.1/forge/build/libs
    "$FORGE_GRADLE" :mc-1.21.1:forge:clean :mc-1.21.1:forge:build \
        :mc-1.21.11:forge:clean :mc-1.21.11:forge:build -x test --no-daemon || true
    copy_jars mc-1.21.1/forge/build/libs mc-1.21.11/forge/build/libs
    restore_settings
else
    echo "  SKIP: gradle-8.9 not found — run scripts/fastbuild/build-forge.bat on Windows"
fi

echo
echo "[Phase 3/4] Fabric 1.21.11 (settings-12111.gradle)..."
switch_settings 12111
"$GRADLE" :mc-1.21.11:fabric:clean :mc-1.21.11:fabric:build -x test || echo "  Fabric 1.21.11 FAILED"
restore_settings
copy_jars mc-1.21.11/fabric/build/libs

echo
echo [Phase 4/5] MC 26.x (settings-26.gradle)...
switch_settings 26
"$GRADLE" \
    :mc-26.1:fabric:clean :mc-26.1:fabric:build \
    :mc-26.2:fabric:clean :mc-26.2:fabric:build \
    :mc-26.2:neoforge:clean :mc-26.2:neoforge:build \
    -x test || echo "  MC 26.x builds had errors"
restore_settings
copy_jars mc-26.1/fabric/build/libs mc-26.2/fabric/build/libs mc-26.2/neoforge/build/libs

echo
echo "============================================"
echo "  Build Complete - output/"
echo "============================================"
ls -1 "$OUTPUT_DIR"/*.jar 2>/dev/null || echo "  (no JARs collected)"
echo "============================================"
