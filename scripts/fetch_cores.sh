#!/usr/bin/env bash
# Baixa os núcleos libretro (arm64-v8a) do buildbot oficial e os coloca em jniLibs
# com o nome lib<core>_libretro.so, que é o que o app procura.
set -euo pipefail
BASE="https://buildbot.libretro.com/nightly/android/latest/arm64-v8a"
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a"
TMP="$(mktemp -d)"
mkdir -p "$OUT"
for core in mgba gambatte fceumm snes9x; do
  echo "→ $core"
  curl -fL "$BASE/${core}_libretro_android.so.zip" -o "$TMP/$core.zip"
  unzip -oq "$TMP/$core.zip" -d "$TMP"
  mv "$TMP/${core}_libretro_android.so" "$OUT/lib${core}_libretro.so"
done
rm -rf "$TMP"
echo "Pronto: $(ls "$OUT")"
