# Third-party components

| Component | Use | License | Notes |
|---|---|---|---|
| [mpv](https://mpv.io) (libmpv) | Video playback | LGPL-2.1+ | Built with `-Dgpl=false`, shipped as `libmpv.so` |
| [FFmpeg](https://ffmpeg.org) | Demuxing/decoding | LGPL-3.0+ | Built without `--enable-gpl` (with `--enable-version3` because of mbedTLS), shared libraries |
| libass, freetype, fribidi, harfbuzz, fontconfig, libunibreak, libxml2, dav1d, libplacebo, Lua 5.2, mbedTLS, curl | libmpv dependencies | ISC / FTL / LGPL-2.1 / MIT / MIT / zlib / MIT / BSD-2 / LGPL-2.1 / MIT / Apache-2.0 / curl | Built from source by the mpv-android build scripts |
| [libbluray](https://www.videolan.org/developers/libbluray.html) | Blu-ray ISO reading | LGPL-2.1+ | Static inside `libmpv.so` (still LGPL as a whole); no BD-J, no AACS |
| [smbj](https://github.com/hierynomus/smbj) | SMB2/3 client | Apache-2.0 | With asn-one (Apache-2.0), Bouncy Castle (MIT-style), SLF4J (MIT), MBassador (MIT) |
| [mpv-android build scripts](https://github.com/mpv-android/mpv-android) | Builds the native libraries (not shipped) | MIT | Patched by `tools/build-libmpv.sh` to turn off GPL options |
| Gradle wrapper | Build tooling (not shipped) | Apache-2.0 | |
| JUnit 4 | Unit tests (not shipped) | EPL-1.0 | |

All 3D scenes in the app are generated procedurally in our own shaders;
there are no bitmap or model assets.

Since libmpv and FFmpeg are LGPL and dynamically linked, a closed-source
build would still have to ship their source (or a written offer), allow
users to replace the `.so` files, and include their license texts.
