# Sideview VR: spec and backlog

## Goal

A phone-VR video player for your own media: a virtual cinema with a big,
adjustable screen; broad format support through libmpv; easy access to a
home media server. Phones first (Cardboard-style viewers), standalone
headsets later.

Ground rules: our own code and our own (procedural or CC0) assets. Other
players are only a feature checklist: no code, assets, names, look or
protocols copied. Core code stays relicensable (GPLv3 + CLA; LGPL-only
native deps).

## Architecture (M1)

- `MainActivity`: full-screen landscape, overlay controls, gestures, keys,
  file/URL/intent opening, settings persistence.
- `Mpv` + `jni/mpv_jni.cpp`: thin JNI binding to libmpv (create, options,
  commands, properties, surface attach, log forwarding to logcat).
- Video path: mpv renders (`vo=gpu`, hardware decoding via MediaCodec
  copy-back) into an Android `Surface` backed by a `SurfaceTexture`; the
  VR renderer samples it as an external OES texture.
- `VrRenderer` (GLES 3.0): per eye, render the room and the screen into an
  offscreen texture, then a distortion pass draws each half of the
  display through the inverse of the lens's radial (pincushion)
  distortion. "Flat" mode draws a single view without distortion.
- `HeadTracker`: `TYPE_GAME_ROTATION_VECTOR` (fallback
  `TYPE_ROTATION_VECTOR`), converted to OpenGL coordinates, with a yaw
  recenter offset.
- `CinemaRoom`: a procedurally shaded room box (wall panels, floor,
  ceiling lights, a soft glow around the screen).
- Default viewer parameters: lens separation 63.9 mm, screen-to-lens
  39 mm, lens center 35 mm above the tray, distortion k1 0.34 / k2 0.55
  (typical Cardboard v2-class viewer values).

## Status (v0.4.0-beta, tested on a ZTE Axon 7 only)

Legend: [x] implemented and builds, [~] implemented but needs hands-on/headset
tuning, [ ] not done.

Viewing
- [x] Stereo rendering with lens distortion; flat mode without headset
- [x] Phone-sensor head tracking, recenter
- [~] Viewer profiles: Cardboard v2, Daydream View (approximate values), Custom
      (lens separation, screen-to-lens, k1/k2)
- [ ] Cardboard viewer QR code scanning
- [ ] Sensor prediction / low-latency front buffer
- [~] In-VR control panel: the phone UI is rendered onto a panel in the room;
      pointer = head gaze or Daydream controller; select = screen tap /
      headset button / controller click
- [x] Seat positions (front / middle / back with raised back row)
- [x] Screen size, distance, curve (flat to wraparound), tilt
- [x] Ambient room glow from the video (32x32 mipmapped copy samples the
      nearest screen edge), room lights and glow sliders
- [x] Picture controls: brightness, contrast, saturation, gamma (mpv)
- [x] Theatre: instanced padded seats (rounded-box model), velvet curtains and
      valance, brighter house lighting; lights slider, dim level, auto-dim 0–15 s
- [~] Point and scroll (controller touchpad / mouse wheel): screen size when
      pointing at the screen, lights when pointing above it (needs headset check)
- [x] Start screen: theatre or phone menu, "always go straight in" option
- [x] Stadium seating: rows on 0.3 m tiers, arcs around a point behind the screen
- [~] Smart view adjust: exponential ease (2.5 s) toward a resting head within 12
      degrees, paused while turning (needs a headset feel check)
- [x] Material 3 dark-scheme menu (platform widgets, no extra library)

Formats
- [x] 3D SBS / TB (half and full), swap eyes, depth offset
- [x] 180°, 360° equirectangular, fisheye 180° (mono or 3D)
- [~] EAC 360° (layout implemented from the common YouTube arrangement;
      verify with a real sample)
- [x] Format auto-detection from file name tags (_sbs, _tb, 180, 360, …)
- [ ] Spherical metadata (sv3d/st3d) detection

Sources
- [x] Local files (system picker, persisted access), "Open with", URLs
- [x] SMB2/3 shares (smbj) streamed through a local HTTP range server;
      works with a home NAS over a VPN (e.g. Tailscale name or IP)
- [x] DLNA/UPnP: SSDP scan on the LAN + add by description URL (needed over
      Tailscale, since multicast doesn't cross it)
- [x] Recents and resume position
- [ ] PC desktop streaming (needs our own protocol and a PC app)

Playback
- [x] Bluetooth audio delay (preset +200 ms, fine steps), playback speed
- [x] Audio / subtitle track cycling, chapters; subtitles render on the screen
- [x] Smart audio/subtitles for a preferred language (SubtitlePicker), checked
      against a real home library of about 850 movies and 380 TV seasons
- [x] Offline copies (SMB file/folder/series, DLNA), resumable, foreground
      service; offline copies are preferred; Wi-Fi-off hint while playing them
- [x] Battery: render on demand, battery saver, view lock, zero-copy decoding
      (MediaCodec straight to the texture when no subtitles), burst buffering
- [~] Daydream controller over BLE: click, app, home, volume, touchpad swipes,
      orientation pointer (axis mapping needs a hands-on check)
- [x] Keyboard / gamepad keys
- [~] Blu-ray ISO (libbluray, no menus/BD-J, unencrypted only, local files only)
- [~] HDR10/HLG: tone-mapped to SDR; Dolby Vision profile 5 colors need the
      gpu-next renderer (Picture tab); no HDR output to the panel yet
- [~] Watch together: host/follow playback clock over TCP (LAN/Tailscale),
      own line protocol; everyone opens the same file

Later
- [ ] Standalone headsets (OpenXR build for Quest/Pico/Android XR)
- [ ] Spatial audio / headphone virtualization
- [ ] Voice chat and avatars for watch together

## Test devices
- Pixel 9 Pro (64-bit only, Android 14+)
- ZTE Axon 7 (Snapdragon 820, LineageOS 17.1 / Android 10)
