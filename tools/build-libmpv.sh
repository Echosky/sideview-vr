#!/bin/bash -e
# Builds LGPL libmpv + FFmpeg for arm64-v8a and armeabi-v7a using the
# mpv-android build scripts (MIT), with all GPL options turned off.
# Usage: tools/build-libmpv.sh [work dir, default ~/sv]
# Afterwards run tools/import-libmpv.sh <work dir>/mpv-android

WORK=${1:-$HOME/sv}
MPV_ANDROID_REV=ebf1b3f0cbe9d6154a43cf6ec1ad6fc32a1b2d47
FFMPEG_TAG=n9.0.2
LIBBLURAY_TAG=1.5.1
REPO=$(cd "$(dirname "$0")/.." && pwd)
# meson >= 1.11 is needed (e.g. a venv: python3 -m venv v && v/bin/pip install meson ninja)

mkdir -p "$WORK" && cd "$WORK"
if [ ! -d mpv-android ]; then
	git clone https://github.com/mpv-android/mpv-android
	git -C mpv-android checkout -q $MPV_ANDROID_REV
fi
cd mpv-android/buildscripts

# LGPL: FFmpeg without --enable-gpl (version3 stays: mbedTLS is Apache-2.0),
# mpv with -Dgpl=false.
sed -i 's/--enable-{gpl,version3}/--enable-version3/' scripts/ffmpeg.sh
grep -q -- '-Dgpl=false' scripts/mpv.sh || \
	sed -i 's/-Dlibmpv=true -Dcplayer=false/-Dlibmpv=true -Dcplayer=false -Dgpl=false/' scripts/mpv.sh
grep -q -- '--enable-gpl' scripts/ffmpeg.sh && { echo "GPL still enabled" >&2; exit 1; }

mkdir -p deps
[ -d deps/ffmpeg ] || git clone --depth 1 -b $FFMPEG_TAG https://github.com/FFmpeg/FFmpeg deps/ffmpeg

# Blu-ray: libbluray (LGPL-2.1, static into libmpv, no BD-J/Java).
if [ ! -d deps/libbluray ]; then
	git clone https://code.videolan.org/videolan/libbluray.git deps/libbluray
	git -C deps/libbluray checkout -q $LIBBLURAY_TAG
	git -C deps/libbluray submodule update --init
fi
cp "$REPO/tools/libbluray.sh" scripts/libbluray.sh
grep -q '^dep_libbluray=' include/depinfo.sh || {
	sed -i 's/^dep_mpv=(\(.*\))/dep_libbluray=(libxml2)\ndep_mpv=(\1 libbluray)/' include/depinfo.sh
}
grep -q -- '-Dlibbluray=enabled' scripts/mpv.sh || \
	sed -i 's/-Dgpl=false/-Dgpl=false -Dlibbluray=enabled/' scripts/mpv.sh

./download.sh
for a in arm64 armv7l; do
	echo "== building for $a"
	./buildall.sh --arch $a mpv
done
echo "== done"
