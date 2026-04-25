#!/usr/bin/env bash
# Converts icon.svg to all Android mipmap PNG sizes.
# Run after updating icon.svg.
set -euo pipefail

echo "==> Generating launcher icons from icon.svg..."

mkdir -p app/src/main/res/mipmap-mdpi
mkdir -p app/src/main/res/mipmap-hdpi
mkdir -p app/src/main/res/mipmap-xhdpi
mkdir -p app/src/main/res/mipmap-xxhdpi
mkdir -p app/src/main/res/mipmap-xxxhdpi

magick -background none icon.svg -resize 48x48   PNG32:app/src/main/res/mipmap-mdpi/ic_launcher.png
magick -background none icon.svg -resize 48x48   PNG32:app/src/main/res/mipmap-mdpi/ic_launcher_round.png
magick -background none icon.svg -resize 72x72   PNG32:app/src/main/res/mipmap-hdpi/ic_launcher.png
magick -background none icon.svg -resize 72x72   PNG32:app/src/main/res/mipmap-hdpi/ic_launcher_round.png
magick -background none icon.svg -resize 96x96   PNG32:app/src/main/res/mipmap-xhdpi/ic_launcher.png
magick -background none icon.svg -resize 96x96   PNG32:app/src/main/res/mipmap-xhdpi/ic_launcher_round.png
magick -background none icon.svg -resize 144x144 PNG32:app/src/main/res/mipmap-xxhdpi/ic_launcher.png
magick -background none icon.svg -resize 144x144 PNG32:app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png
magick -background none icon.svg -resize 192x192 PNG32:app/src/main/res/mipmap-xxxhdpi/ic_launcher.png
magick -background none icon.svg -resize 192x192 PNG32:app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png

echo "==> Done. Icons written to mipmap-* directories."
