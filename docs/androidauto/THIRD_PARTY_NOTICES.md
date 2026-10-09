# Credits and license notices

Revv's Android Auto stack (`androidauto/`) is DiAuto, merged into Revv; these notices are DiAuto's, with paths
as they are in Revv.

## The head unit

DiAuto is a modified version of [Open Headunit](https://github.com/andreknieriem/open-headunit) by Andre
Rinas, itself a continuation of [headunit](https://github.com/mikereidis/headunit) by Michael A. Reid. Both
are licensed under the GNU Affero General Public License, version 3; the full text is in
`docs/androidauto/licenses/AGPL-3.0.txt` and Michael Reid's copyright notice is in
`docs/androidauto/COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt`. DiAuto's README is retained in
`docs/androidauto/UPSTREAM-README.md` and its changelog in `docs/androidauto/CHANGELOG.md`. Existing source
comments and attribution are preserved.

## Runtime dependencies

- AndroidX, including AndroidX Media — Android Open Source Project; Apache License 2.0.
- Kotlin standard library and kotlinx.coroutines — JetBrains; Apache License 2.0.
- Protocol Buffers 3.25.1 (`protobuf-java`) — Google; BSD 3-Clause.
- DexMaker 2.28.3 — Google / LinkedIn; Apache License 2.0.

Gradle dependency declarations and the version catalog accompany the source, and Revv's
Settings › System › Open source licenses lists them.

Not carried over from DiAuto: FFmpeg (LGPL-2.1-or-later, its bundled ARM64 software HEVC decoder), libusb
(LGPL-2.1-or-later), Google Play services Nearby Connections, and the root and Shizuku helpers. Revv decodes
with Android's own MediaCodec and uses Android's USB accessory API.

## Android Auto head-unit key pair

`androidauto/src/main/res/raw/privkey` and `cert` are the Android Auto head-unit development key pair
(subject "Google-Android-Reference") that headunit, Open Headunit, DiAuto and every other open-source head
unit ship; the phone accepts it as the head unit's identity. It is public and not a secret of Revv's or
anyone's. The repository's credential check (`scripts/check_public_tree.py`) lists it as the one allowed
private-key block.

## Trademarks

Android Auto is a trademark of Google LLC. BYD belongs to its respective owner. Neither company endorses
Revv.

## BYD HUD maneuver icons

DiAuto ships BYDMate's head-up display maneuver PNGs (PolyForm Noncommercial 1.0.0, Copyright AndyShaman,
https://github.com/AndyShaman/BYDMate). Revv carries them in neither stack: they were the one non-free piece,
and the BYD outputs use the car's own turn codes. The HUD bridge looks for them and sends no picture for the
manoeuvres that have no arrow when it finds none.
