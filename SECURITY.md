# Security

## CarPlay accessory identity

CarPlay needs an Apple accessory identity (`identity.pk8` and `certificate.p7b`). Revv ships none, the
repository contains none, and no release ever bundles one: a key inside an APK can be extracted by anyone
who has the APK. Drivers import their own in Settings › CarPlay; it is kept in Revv's private storage on
the head unit (`noBackupFilesDir`, so it stays out of Android backups) and never leaves it.

Debug builds bundle the developer's own identity from the gitignored `.private/auth/offline-mfi/` when it is
there, for test installs. The build refuses any other credential file in an APK's assets, the release
lanes refuse a release that contains one, and CI (`scripts/check_public_tree.py`) refuses credential
containers and private-key blocks anywhere in the tracked tree.

## Android Auto head-unit key

Android Auto asks the head unit to prove itself with a key pair, and Revv ships one:
`androidauto/src/main/res/raw/privkey` and `cert`. That is the public Android Auto head-unit *development*
pair (subject "Google-Android-Reference") that [headunit](https://github.com/mikereidis/headunit),
[Open Headunit](https://github.com/andreknieriem/open-headunit), [DiAuto](https://github.com/shihabal3amri/DiAuto)
and every other open-source head unit have shipped for years; the phone accepts it as the head unit's identity.
It is secret to no one and protects nothing of yours: the Android Auto link itself is encrypted session by
session with keys agreed on the spot. It is the one private-key block the tree check allows, by name.

## Android Auto and CarPlay link details

The car hotspot's password, the phone a wireless link wakes and the head unit's own Wi-Fi Direct group are
kept in Revv's private storage and left out of Android's backups and device transfers (`backup_rules.xml`,
`data_extraction_rules.xml`), as are CarPlay's pairing records.

## Signing

The Android release signing key stays in the maintainer's hands (the release workflow's secrets) and is
never in the tree or in a build.

## Reporting

Diagnostic reports (Settings › CarPlay › Diagnostic report and Settings › Android Auto › Diagnostic report) are
saved on the head unit only; review one before posting it, and never include identity files, pairing records
or hotspot passwords in a public issue. Use GitHub's private vulnerability reporting for anything sensitive.
