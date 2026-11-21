#!/bin/bash -e
set -eo pipefail  # also when run as "bash script"
# Copies libmpv/FFmpeg headers and shared libraries from an mpv-android
# build tree into app/prebuilt/<abi>/ (git-ignored).
# Usage: tools/import-libmpv.sh <path to mpv-android checkout>

SRC=${1:?path to mpv-android}/buildscripts/prefix
DST=$(cd "$(dirname "$0")/.." && pwd)/app/prebuilt
LIBS="libmpv.so libavcodec.so libavformat.so libavutil.so libavfilter.so libswresample.so libswscale.so libavdevice.so"

for pair in arm64:arm64-v8a armv7l:armeabi-v7a; do
	arch=${pair%%:*}; abi=${pair##*:}
	[ -d "$SRC/$arch" ] || { echo "skip $abi (not built)"; continue; }
	rm -rf "$DST/$abi"; mkdir -p "$DST/$abi/lib" "$DST/$abi/include"
	cp -r "$SRC/$arch/include/mpv" "$SRC/$arch/include/libavcodec" "$SRC/$arch/include/libavutil" "$DST/$abi/include/"
	for l in $LIBS; do
		[ -f "$SRC/$arch/lib/$l" ] && cp -L "$SRC/$arch/lib/$l" "$DST/$abi/lib/"
	done
	# GPL guard: refuse libraries built with GPL options.
	if strings "$DST/$abi/lib/libavcodec.so" | grep -q -- '--enable-gpl'; then
		echo "ERROR: $abi FFmpeg was built with --enable-gpl" >&2; exit 1
	fi
	echo "$abi: $(ls "$DST/$abi/lib" | tr '\n' ' ')"
done
