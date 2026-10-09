# Android Auto documentation

Revv's Android Auto stack (`androidauto/`) is a fork of [DiAuto](https://github.com/shihabal3amri/DiAuto) 0.3.11,
itself based on [Open Headunit](https://github.com/andreknieriem/open-headunit) and Michael Reid's original
[headunit](https://github.com/mikereidis/headunit), all under the GNU AGPL 3.0. The documents in this folder
are DiAuto's, carried over for reference and attribution: connection setup, the car hotspot and Wi-Fi Direct
notes, BYD navigation outputs, the upstream README and changelog, the third-party notices and the licenses.

They predate the merge into Revv. Only the Android Auto engine came across (the protocol, `AapService`, the
USB, Wi-Fi Direct and car hotspot links, the video and audio decoders, the media session, navigation and BYD
cluster outputs) with a new `embed/` package that Revv's Auto screen and Settings › Android Auto drive
(`AndroidAutoHost.kt`); DiAuto's own screens, its full-screen projection activity, its home screen, settings
dialogs, Nearby Connections, root and Shizuku helpers, libusb and FFmpeg software decoding did not. Where these
documents say to open DiAuto or its settings, use Revv's **Settings › Android Auto**, and where they build
`:app:assembleGithubDebug`, build Revv's `:app:assembleDebug`.

What Revv changed in the engine:

- `HeadUnitScreenConfig` takes a *host view* (the Auto screen's panel) as the usable area instead of the whole
  display, picks the smallest standard resolution that holds it, and sizes Android Auto's interface by
  **Settings › Android Auto › Android Auto size** rather than the display's density alone.
- Preferences live in `android_auto` (not `settings`) so they never collide with Revv's own, and the native
  hotspot helper is `libaa_hotspot_radio.so` so it never collides with the CarPlay stack's.
- Turn-by-turn directions reach Revv's map through `AapNavigationHelper.onNavigationUpdate` as well as the
  broadcast DiAuto sends.
- The Android Auto head-unit key pair in `src/main/res/raw/` is the public development pair every open-source
  head unit uses (see `SECURITY.md` at the repository root); nothing about it is secret.
