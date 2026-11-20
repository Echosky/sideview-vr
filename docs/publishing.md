# Publishing (staged, not submitted)

Status: **beta 0.4.0**, tested on a ZTE Axon 7 only. Nothing has been submitted to any store yet.

## Store listing

The listing lives in [`fastlane/metadata/android/en-US`](../fastlane/metadata/android/en-US) in the
layout F-Droid and IzzyOnDroid read: title, short and full description, changelog per `versionCode`,
icon, feature graphic and phone screenshots. NeoStore shows the same data from those repositories.

## F-Droid (builds from source)

1. Fork [fdroiddata](https://gitlab.com/fdroid/fdroiddata) and add
   [`fdroid/app.sideviewvr.yml`](../fdroid/app.sideviewvr.yml) as `metadata/app.sideviewvr.yml`.
2. Run `fdroid lint app.sideviewvr` and `fdroid build -v -l app.sideviewvr` locally (fdroidserver
   in Docker) and fix what it reports.
3. Open a merge request. The native libraries (libmpv, FFmpeg, libbluray and their dependencies) must
   build from source on F-Droid's server; the recipe calls `tools/build-libmpv.sh`, which downloads
   sources during the build. F-Droid reviewers may ask for those to become `srclibs`
   (mpv-android's own F-Droid recipe is the model to follow).

No anti-features are expected: no tracking, no ads, no non-free dependencies or network services.

## IzzyOnDroid (and so NeoStore), faster route

IzzyOnDroid publishes the APK from GitHub Releases, so it needs a **release-signed** APK:

1. Create a signing key once and keep it safe (losing it means users can't update):
   `keytool -genkeypair -v -keystore sideview-release.jks -alias sideview -keyalg RSA -keysize 4096 -validity 10000`
   Never commit the keystore or its passwords.
2. Build and sign: `./gradlew assembleRelease`, then
   `apksigner sign --ks sideview-release.jks --out SideviewVR-0.4.0-beta.apk app/build/outputs/apk/release/app-release-unsigned.apk`.
3. Attach the APK to the GitHub release `v0.4.0-beta` and request inclusion at
   [IzzyOnDroid](https://gitlab.com/IzzyOnDroid/repo/-/issues).

APKs signed with different keys (debug, your key, F-Droid's key) cannot update each other; testers
moving between them must uninstall first.
