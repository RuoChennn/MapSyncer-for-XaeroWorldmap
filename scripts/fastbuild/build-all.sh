#!/bin/bash
cd "$(dirname "$0")/../.."
echo "========================================"
echo "  MapSyncer - Build All Versions"
echo "========================================"
echo

./gradlew build -x test --parallel collectJars

echo
echo "========================================"
echo "  Build Complete! Output: build/lib/"
echo "========================================"
ls -la build/lib/*.jar 2>/dev/null || echo "No JARs found in build/lib"
