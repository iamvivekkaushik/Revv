<p align="center">
  <img src="fastlane/metadata/android/en-US/images/featureGraphic.png" alt="Revv: the home screen your head unit should have shipped with" width="100%">
</p>

# Revv

**A car launcher for Android head units.** Revv replaces the head unit's home screen with a dashboard built for driving: live gauges from the engine over OBD-II, maps and turn-by-turn directions, calls and contacts from your phone over Bluetooth, the rear camera and your apps, each a tap away and sized for a glance.

[Download the latest APK](https://github.com/iamvivekkaushik/Revv/releases/latest) · [Website](https://iamvivekkaushik.github.io/Revv/) · [Privacy policy](https://iamvivekkaushik.github.io/Revv/privacy.html)

## Screenshots

![Home: speed, engine speed and gear, fuel range, now playing, and the map with the next turn](fastlane/metadata/android/en-US/images/phoneScreenshots/1_home.png)

| Navigation | Vehicle |
| --- | --- |
| ![Turn-by-turn navigation on the full map](fastlane/metadata/android/en-US/images/phoneScreenshots/2_navigation.png) | ![Live OBD-II readings and fault codes in plain English](fastlane/metadata/android/en-US/images/phoneScreenshots/3_vehicle.png) |
| **Settings** | |
| ![Home layout settings](fastlane/metadata/android/en-US/images/phoneScreenshots/4_settings.png) | |

The screenshots use the demo drive and the simulated OBD-II adapter.

## Features

- **Live OBD-II gauges** from an ELM327 adapter over Bluetooth, Bluetooth LE or Wi-Fi: speed, engine speed, load, throttle, coolant, intake air and battery voltage.
- **Gear indicator**, worked out from the gearbox ratios and tyre size, or learnt as you drive.
- **Engine sound** (experimental): a synthesised engine through the speakers that follows the car's rpm, load and gear changes, after [engine-sim](https://github.com/ange-yaghi/engine-sim)'s synthesizer. Pick an inline 3 to a V12, from a stock exhaust to a straight pipe, turn its bass up or down, hear each bank's pipe from its own side in surround, with optional pops on lift-off, in **Settings › Sound**.
- **Fault codes** in plain English, pending codes, clearing them, and low-battery and check-engine warnings with a badge on the dock.
- **Maps and directions** on OpenStreetMap: place search, turn-by-turn routes with the next turn on the home screen, avoiding tolls, north-up or heading-up.
- **Your phone over Bluetooth**, the way a car kit reads it: recent calls, favourites, contacts with A–Z quick scroll, a T9 dialer, and calls with a call bar and end-call button.
- **Rear camera**, live from the head unit, with parking guides, rotation and camera switching.
- **Apps and projection**: every installed app, a projection app of your choice, and now playing from your music app, seeking by dragging its progress bar or, if you like, swiping the card.
- **CarPlay**, built in: wired over USB or wireless, the iPhone's screen runs in the Auto screen's panel, touch included, or over the whole screen, and keeps playing while you use the rest of Revv. Its settings (identity, link, iPhone, display, audio, location) are in **Settings › CarPlay**. Revv ships no Apple accessory identity; you import your own there. The CarPlay stack is [DiPlay](https://github.com/shihabal3amri/DiPlay)'s, a fork of [xcertplay](https://github.com/shilapi/xcertplay) (GPL-3.0), in `carplay/`.
- **Android Auto**, built in the same way: plug an Android phone in over USB, or pair it and let it join over Wi-Fi Direct or the car's hotspot, and its Android Auto runs in the Auto screen's panel or over the whole screen, touch, assistant and turn-by-turn included, and keeps playing while you use the rest of Revv. Its settings (link, phone, display, audio, location) are in **Settings › Android Auto**; the Auto screen's **PHONE** card switches between CarPlay and Android Auto. The Android Auto stack is [DiAuto](https://github.com/shihabal3amri/DiAuto)'s, a fork of [Open Headunit](https://github.com/andreknieriem/open-headunit) and [headunit](https://github.com/mikereidis/headunit) (AGPL-3.0), in `androidauto/`.
- **Make it yours**: choose the home screen's cards and panels, turn the fuel card into an app shortcut, set display size (80–160%, with compact layouts above 130%), date and time formats, sunset or light-sensor dimming, and tap sounds.
- **Demo drive**: simulated car data while no adapter is set up, so every gauge can be tried at a desk.

No account, ads or analytics. Calls, contacts, car data, the camera picture, CarPlay and Android Auto (direct links between the head unit and the phone) stay on the head unit; maps, routes and place search use public OpenStreetMap services ([OpenFreeMap](https://openfreemap.org), [Valhalla](https://valhalla1.openstreetmap.de), [Photon](https://photon.komoot.io)). The [privacy policy](https://iamvivekkaushik.github.io/Revv/privacy.html) has the details.

## Install

1. Download `revv-<version>.apk` from the [latest release](https://github.com/iamvivekkaushik/Revv/releases/latest) onto the head unit and open it. Android asks to allow installs from that source once.
2. Open Revv and tap **Set as home**, or pick Revv when Android asks which app should be the home app.
3. Optional: pair your phone in Android's Bluetooth settings and allow it to share contacts and call history; set up an OBD-II adapter in **Settings › Vehicle**.
4. Optional, for CarPlay: in **Settings › CarPlay**, import your identity files (`identity.pk8` and `certificate.p7b`), pick the link and, for wireless, your iPhone. Then open **AUTO** and tap **FINISH SETUP** once to allow the permissions and, for USB, the VPN connection CarPlay runs over.
5. Optional, for Android Auto: open **AUTO**, tap **ANDROID AUTO** on its PHONE card and plug the phone in; allow Revv to use it when Android asks. For wireless, in **Settings › Android Auto** allow the permissions, pick **Wi-Fi Direct** or save the car hotspot's name and password, and choose the paired phone; Revv then wakes it over Bluetooth when it connects to the head unit, when Revv starts, when the Auto screen opens, and on **CONNECT**. A session the phone stops answering is reconnected by itself. Revv listens for the phone the PHONE card shows: switching it stands the other down, unless that one is mid-session, since the head unit's Wi-Fi Direct radio hosts one group at a time.

Revv needs Android 9 or newer and a landscape screen. It lays out a 1920×1080 artboard that scales to the screen, and stretches to fill wide ones such as 1920×720.

## Build from source

You need Android Studio (or the Android SDK with API 37 and NDK 28.2, which the CarPlay and Android Auto stacks' three native files build with) and a JDK to start Gradle; Android Studio's bundled JBR works. Gradle provisions the JDK 25 its daemon runs on.

```bash
./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Debug builds also offer a **Simulated ELM327** adapter that answers like a real one from the demo drive, and cycles through a low battery, a pending code and two stored fault codes, so the whole OBD-II path can be tried without a car.

Debug builds bundle your own CarPlay identity when `.private/auth/offline-mfi/identity.pk8` and `certificate.p7b` are there (the folder is gitignored), so test installs need no import. Release builds never bundle one: anyone with the APK could extract the key. The build refuses any other credential file in the APK's assets, and CI refuses them in the tree (`scripts/check_public_tree.py`).

Run the unit tests:

```bash
./gradlew :app:testDebugUnitTest :carplay:shared:testDebugUnitTest :carplay:common:testDebugUnitTest :androidauto:testDebugUnitTest
```

### Project layout

| Path | What's there |
| --- | --- |
| `app/src/main/java/com/vivekkaushik/revv/ui/hmi` | The Compose UI: home screen, the apps over it, the dock and settings |
| `…/carplay` | CarPlay as the Auto screen and Settings › CarPlay see it: the session's state, its settings and route guidance |
| `…/androidauto` | Android Auto the same way, for the Auto screen and Settings › Android Auto |
| `…/obd` | ELM327 transports (Bluetooth, BLE, Wi-Fi, simulated), PIDs, fault codes and the polling session |
| `…/phone` | PBAP (calls and contacts) and HFP (dialling) over Bluetooth |
| `…/nav` | Location, routing, place search and guidance |
| `…/vehicle` | Car setup, gear estimation, trip computer and the demo drive |
| `…/engine` | The engine sound: layouts, the pulse model and engine-sim's synthesizer, and the audio player |
| `…/system`, `…/media`, `…/apps` | Brightness and night dimming, now playing, installed apps |
| `carplay/shared` | The CarPlay stack from DiPlay / xcertplay: iAP2, AirPlay, MFi authentication, the USB and wireless links, video and audio, BYD cluster outputs |
| `carplay/common` | The CarPlay session engine Revv hosts (`embed/`): its settings, media session, connection service and diagnostics |
| `docs/carplay/` | DiPlay's documentation, notices and changelog |
| `androidauto/` | The Android Auto stack from DiAuto / Open Headunit / headunit: the Android Auto protocol, the USB, Wi-Fi Direct and car hotspot links, video and audio, navigation and BYD cluster outputs, and the session engine Revv hosts (`embed/`) |
| `docs/androidauto/` | DiAuto's documentation, notices and changelog |
| `site/` | The website: landing page and privacy policy |
| `fastlane/` | Lanes, Play Store listing and store graphics |

### Signed release APK

The release build is signed only when all four `REVV_KEYSTORE_*` variables are set; otherwise it is unsigned.

1. Create a keystore once (keep it and its passwords; every update must use the same key):
   ```bash
   "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" -genkeypair -v -keystore ~/revv-release.jks -alias revv -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Build:
   ```bash
   JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" REVV_KEYSTORE_PATH=~/revv-release.jks REVV_KEYSTORE_PASSWORD='…' REVV_KEY_ALIAS=revv REVV_KEY_PASSWORD='…' ./gradlew :app:assembleRelease
   ```
   Output: `app/build/outputs/apk/release/app-release.apk`. Use `:app:bundleRelease` for a Play bundle.
3. Check the signature:
   ```bash
   ~/Library/Android/sdk/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   ```

## Releases

Releases are built in CI with [fastlane](https://fastlane.tools). Pushing a `vMAJOR.MINOR.PATCH` tag builds the release APK and app bundle signed with the release keystore and publishes a GitHub release with both attached (plus the R8 mapping, for reading the APK's crash logs), a changelog of the commits since the previous tag and SHA-256 checksums. A suffixed tag such as `v1.2.0-beta.1` becomes a pre-release.

```bash
git tag v1.0.0 && git push origin v1.0.0
```

The version comes from the tag: `v1.2.3` is version name `1.2.3` and version code `1002003`.

| Repository secret | Value |
| --- | --- |
| `REVV_KEYSTORE_BASE64` | The release keystore, base64-encoded (`base64 -i release.jks`) |
| `REVV_KEYSTORE_PASSWORD`, `REVV_KEY_ALIAS`, `REVV_KEY_PASSWORD` | The keystore's password, the key's alias and the key's password |
| `PLAY_STORE_JSON_KEY` | Optional: a Google Play service account's JSON key, to upload to Play as well |

With `PLAY_STORE_JSON_KEY` set, final releases also go to Google Play: the internal track unless the `PLAY_TRACK` repository variable says `alpha`, `beta` or `production`, and released straight away unless `PLAY_RELEASE_STATUS` is `draft`. Pre-releases stay off Play.

The store listing in `fastlane/metadata/android` (title, descriptions, icon, feature graphic and screenshots) goes up to Play by itself whenever it changes on `master`, with the same key: the **Update Play listing** workflow, which can also be run from the Actions tab. Each version's "What's new" goes up with its release instead.

### fastlane lanes

Install fastlane with `bundle install` (Ruby 3), then run `bundle exec fastlane <lane>`:

| Lane | What it does |
| --- | --- |
| `test` | Runs the unit tests |
| `debug` | Builds the debug APK |
| `install` | Tests, then installs the debug build on the connected device |
| `release`, `bundle` | Tests, then builds the release APK or app bundle (signed when `REVV_KEYSTORE_PATH` and its passwords are set) |
| `listing` | Uploads the Play Store title, descriptions, icon, feature graphic and screenshots from `fastlane/metadata/android` |
| `play` | Builds the signed bundle and uploads it to Play: `version:1.2.0 track:internal status:completed` |
| `publish` | CI only: the GitHub release for the pushed tag |

The store icon and feature graphic are rendered from `fastlane/store-graphics` with `fastlane/store-graphics/render.sh`.

## Website

`site/` holds the landing page and privacy policy. The **Deploy site** workflow publishes it to GitHub Pages whenever it changes; set **Settings › Pages › Source** to **GitHub Actions** once.

## License

Revv is free software under the [GNU GPL 3.0](LICENSE): the CarPlay stack in `carplay/` is a fork of [DiPlay](https://github.com/shihabal3amri/DiPlay) and [xcertplay](https://github.com/shilapi/xcertplay) (GPL-3.0), and the Android Auto stack in `androidauto/` is a fork of [DiAuto](https://github.com/shihabal3amri/DiAuto), [Open Headunit](https://github.com/andreknieriem/open-headunit) and Michael Reid's [headunit](https://github.com/mikereidis/headunit), which stays under the [GNU AGPL 3.0](docs/androidauto/licenses/AGPL-3.0.txt). Keep these notices when distributing modifications. Credits for everything in the CarPlay stack are in [docs/carplay/THIRD_PARTY_NOTICES.md](docs/carplay/THIRD_PARTY_NOTICES.md), and for the Android Auto stack in [docs/androidauto/THIRD_PARTY_NOTICES.md](docs/androidauto/THIRD_PARTY_NOTICES.md).

CarPlay is a trademark of Apple Inc. and Android Auto a trademark of Google LLC; Revv is not an Apple-certified or Google-certified product and is not affiliated with Apple or Google. Revv ships no Apple accessory identity; where you get yours is your own responsibility. The Android Auto head-unit key it ships is the public development pair every open-source head unit uses (see [SECURITY.md](SECURITY.md)).

## Credits

Maps © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors, tiles from [OpenFreeMap](https://openfreemap.org) on the [OpenMapTiles](https://openmaptiles.org) schema, routing by [Valhalla](https://github.com/valhalla/valhalla) and place search by [Photon](https://github.com/komoot/photon). The engine sound follows [engine-sim](https://github.com/ange-yaghi/engine-sim) by Ange Yaghi and plays its exhaust impulse responses (MIT). CarPlay is [DiPlay](https://github.com/shihabal3amri/DiPlay)'s stack, from [xcertplay](https://github.com/shilapi/xcertplay) by shilapi, with [Bouncy Castle](https://www.bouncycastle.org), [JmDNS](https://github.com/jmdns/jmdns) and [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass). Android Auto is [DiAuto](https://github.com/shihabal3amri/DiAuto)'s stack, from [Open Headunit](https://github.com/andreknieriem/open-headunit) by Andre Rinas and [headunit](https://github.com/mikereidis/headunit) by Michael A. Reid, with [Protocol Buffers](https://github.com/protocolbuffers/protobuf) and [DexMaker](https://github.com/linkedin/dexmaker). Built with Jetpack Compose and [MapLibre Native](https://github.com/maplibre/maplibre-native), set in [Michroma](https://github.com/googlefonts/Michroma-font) and [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono). The app's **Settings › System › Open source licenses** lists every library and its license.
