# Changelog

All notable changes to Sideview VR. Versions follow [semantic versioning](https://semver.org/);
`-beta` releases are not yet considered stable.

## [Unreleased]

### Added
- Black void: the theatre fades away while the movie plays (or choose the void permanently)
- Laser pointer from the Daydream controller to what it points at
- Sharpness setting (render resolution), independent of battery saver
- Donation link (Buy Me a Coffee)

### Fixed
- Daydream controller reconnects automatically; a controller button leaves the start screen
- The pointer dot fades out when the pointer rests

## [0.4.0-beta] - 2026-11-20

First public beta. Tested on a ZTE Axon 7 (Android 10) with a Daydream View headset only.

### Added
- Virtual cinema: stadium seating with curved rows of padded seats, velvet curtains, step lights,
  house lights that dim 0-15 s after the movie starts, room lit by the picture, 11 m screen
- Point and scroll: resize the screen or dim the lights with the Daydream touchpad or a mouse wheel
- Smart view adjust: the screen eases gently to where your head rests
- Start screen (theatre or phone menu) and a Material 3 style menu
- Smart audio and subtitles for a preferred language (scrollable list)
- SMB shares, DLNA servers, URLs, local files; subtitle files next to SMB videos
- Offline copies of files, folders and whole series (resumable)
- Battery: render on demand, battery saver, view lock, zero-copy decoding, burst buffering
- Daydream controller and Bluetooth mouse support; keyboards and gamepads
- 3D SBS/TB, 180/360/fisheye/EAC, HDR tone mapping, Blu-ray ISO (unencrypted)
- Watch together (synced playback between phones)

[0.4.0-beta]: https://github.com/Echosky/sideview-vr/releases/tag/v0.4.0-beta
