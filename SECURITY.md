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

## Signing

The Android release signing key stays in the maintainer's hands (the release workflow's secrets) and is
never in the tree or in a build.

## Reporting

Diagnostic reports (Settings › CarPlay › Diagnostic report) are saved on the head unit only; review one
before posting it, and never include identity files or pairing records in a public issue. Use GitHub's
private vulnerability reporting for anything sensitive.
