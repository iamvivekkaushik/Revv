# Credits and license notices

Revv's CarPlay stack (`carplay/`) is DiPlay, merged into Revv; these notices are DiPlay's, with paths as they are in Revv.

## Receiver

DiPlay is a modified version of [xcertplay by shilapi](https://github.com/shilapi/xcertplay). The upstream receiver is licensed under GNU GPL version 3; the full text is in `LICENSE` and the original README is retained in `docs/carplay/UPSTREAM-README.md`.

Upstream credits [LIVI](https://github.com/f-io/LIVI) and [Showcase](https://github.com/amineross/showcase) for protocol research. Existing source comments and attribution are preserved.

## CarPlay icon

The unmodified icon was obtained from Apple's developer site at:

https://developer.apple.com/assets/elements/icons/carplay/carplay-96x96_2x.png

CarPlay and the CarPlay icon are Apple Inc. marks/assets. This asset is not covered by the project's open-source code license. Its use here does not imply Apple approval or certification.

## Runtime dependencies

- AndroidX — Android Open Source Project; Apache License 2.0.
- Kotlin standard library — JetBrains; Apache License 2.0.
- Bouncy Castle 1.79 — The Legion of the Bouncy Castle Inc.; Bouncy Castle license (MIT-style).
- JmDNS 3.6.3 — JmDNS contributors; Apache License 2.0.
- SLF4J — QOS.ch; MIT license.
- AndroidHiddenApiBypass 6.1 (`org.lsposed.hiddenapibypass`) — LSPosed; Apache License 2.0.

Gradle dependency declarations and version catalog accompany the source. License files available in the resolved artifacts are included under `docs/carplay/licenses/dependencies/`, and Revv's Settings › System › Open source licenses lists them.

## Accessory identity

Revv ships no Apple accessory identity and the repository contains none. Drivers import their own key and certificate in Settings › CarPlay; see `SECURITY.md`. The separate Android APK-signing key is never distributed.

## BYD HUD maneuver icons

DiPlay ships maneuver PNGs for BYD windshield head-up displays imported from BYDMate (Copyright AndyShaman,
https://github.com/AndyShaman/BYDMate), under the PolyForm Noncommercial 1.0.0 license. Revv does not carry
them: they were the one non-free piece of the stack, and the validated DiLink 5.1 windshield path uses the
car's own turn codes rather than these images. Without them the HUD bridge sends no picture for the
manoeuvres that have no arrow (roundabouts, the destination); everything else is unchanged.
