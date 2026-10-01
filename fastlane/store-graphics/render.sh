#!/bin/sh
# Renders the Play Store icon and feature graphic from the sources here into fastlane's metadata,
# with headless Google Chrome. Run it after changing icon.svg, feature.html or the home screenshot.
set -e
cd "$(dirname "$0")"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
OUT=../metadata/android/en-US/images

render() { # source, width, height, output
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
    --allow-file-access-from-files --virtual-time-budget=3000 \
    --window-size="$2,$3" --screenshot="$PWD/$4" "file://$PWD/$1" 2>/dev/null
}

render icon.svg 512 512 "$OUT/icon.png"
render feature.html 1024 500 "$OUT/featureGraphic.png"
echo "Rendered $OUT/icon.png and $OUT/featureGraphic.png"
