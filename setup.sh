#!/usr/bin/env bash
# Downloads whisper.cpp source into the JNI build directory.
# Run this once before opening the project in Android Studio.
set -euo pipefail

WHISPER_TAG="v1.5.5"   # flat ggml structure, compiles cleanly with NDK r23
TARGET_DIR="app/src/main/cpp/whisper-cpp"

echo "==> Setting up Transcribe app"

# Rename old clone if it was placed in the wrong directory
if [ -d "app/src/main/cpp/whisper.cpp/.git" ] && [ ! -d "$TARGET_DIR/.git" ]; then
    echo "==> Renaming whisper.cpp/ -> whisper-cpp/ ..."
    mv "app/src/main/cpp/whisper.cpp" "$TARGET_DIR"
fi

# ── whisper.cpp source ───────────────────────────────────────────────────────
if [ -d "$TARGET_DIR/.git" ]; then
    echo "==> whisper-cpp already present, switching to $WHISPER_TAG..."
    git -C "$TARGET_DIR" fetch --tags --quiet
    git -C "$TARGET_DIR" checkout "$WHISPER_TAG" --quiet
else
    echo "==> Cloning whisper.cpp $WHISPER_TAG..."
    git clone --branch "$WHISPER_TAG" --depth 1 \
        https://github.com/ggerganov/whisper.cpp.git "$TARGET_DIR"
fi

echo "==> whisper-cpp ready at $TARGET_DIR"
echo ""
echo "==> Open the project in Android Studio and build."
echo "    The first build compiles whisper.cpp (a few minutes)."
echo ""
echo "==> On first launch the app offers to download ggml-tiny.bin (~39 MB)."
echo "    You can also tap 'Select Model' to pick a .bin file you already have."
