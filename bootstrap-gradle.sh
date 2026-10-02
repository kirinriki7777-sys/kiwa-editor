#!/usr/bin/env bash
set -euo pipefail
VERSION=9.6.0
ROOT="$(cd "$(dirname "$0")" && pwd)"
CACHE="$ROOT/.gradle-bootstrap"
ZIP="$CACHE/gradle-$VERSION-bin.zip"
DIST="$CACHE/gradle-$VERSION"
mkdir -p "$CACHE"
if [[ ! -x "$DIST/bin/gradle" ]]; then
  if [[ ! -f "$ZIP" ]]; then
    echo "Downloading Gradle $VERSION..."
    curl -fL "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$ZIP"
  fi
  unzip -q -o "$ZIP" -d "$CACHE"
fi
exec "$DIST/bin/gradle" "$@"
