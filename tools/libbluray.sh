#!/bin/bash -e
# mpv-android build script for libbluray (LGPL-2.1), static, no BD-J.
# Installed into mpv-android/buildscripts/scripts/ by tools/build-libmpv.sh.

. ../../include/path.sh

build=_build$ndk_suffix

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
	rm -rf $build
	exit 0
else
	exit 255
fi

unset CC CXX

meson setup $build --cross-file "$prefix_dir"/crossfile.txt \
	--default-library static \
	-Dbdj_jar=disabled -Dembed_udfread=true \
	-Dfontconfig=disabled -Dfreetype=disabled -Dlibxml2=enabled \
	-Denable_tools=false -Denable_devtools=false -Denable_examples=false -Denable_docs=false

ninja -C $build -j$cores
DESTDIR="$prefix_dir" ninja -C $build install
