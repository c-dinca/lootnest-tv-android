# Lootnest for TV (Android TV, Google TV, Fire TV)

The TV app of [Lootnest](https://github.com/c-dinca/lootnest), the Windows
game manager with profiles: it plays the games on your PC on a TV, with a
controller, inside Lootnest's TV mode.

Lootnest on the PC installs this app itself: Settings, TV, **Add an Android
TV**. Turn on network debugging in the TV's Developer options first (on a
Fire TV, ADB debugging); when Lootnest connects, the TV asks once to allow
the computer. Lootnest then installs the app, opens it with the PC's
address and a pairing PIN, and hands the same PIN to its streaming engine,
so nothing is typed on the TV.

It is a fork of [Moonlight for Android](https://github.com/moonlight-stream/moonlight-android)
(GPL v3), whose README is [README.moonlight.md](README.moonlight.md). What
this fork changes:

- its own application id, `com.lootnest.tv`, and name, icons and TV banner
  in Lootnest's look (`app/build.gradle`, `app/src/main/res`);
- its own first screen, `LootnestActivity`, which the TV's launcher opens:
  it adds the PC from the `host` it's given, pairs with `pairPin`, opens the
  host app named Lootnest, wakes a sleeping PC, and waits with Play,
  Settings, PCs and Quit after a stream. Moonlight's PC list is still there
  under PCs. Every step is logged with the tag `LootnestTV`, which Lootnest
  reads back over ADB when something goes wrong;
- the fonts Tilt Warp and Atkinson Hyperlegible Next (SIL OFL,
  `app/src/main/assets/fonts/OFL.txt`).

To come back to Lootnest for TV's screen during a stream, press Start,
Select, LB and RB together, as in Moonlight.

## Releases

`VERSION` is the app's version. A push to main that changes it makes the
release `v<VERSION>` with the signed `com.lootnest.tv_<VERSION>.apk` and its
SHA-256 (`.github/workflows/release.yml`); Lootnest pins that address and
checksum in `core.AndroidAppPinned` once the release has been tried.

Releases are signed with one key, kept as four repository secrets:
`LOOTNEST_KEYSTORE_B64` (the keystore file, base64), `LOOTNEST_KEYSTORE_PASSWORD`,
`LOOTNEST_KEY_ALIAS` and `LOOTNEST_KEY_PASSWORD`. Keep a copy of the
keystore somewhere safe: a TV only takes an update signed with the same key.

To build here: `./gradlew assembleNonRootDebug` (Android SDK with platform
37 and NDK 29.0.14206865).

## License

GPL v3, as Moonlight: [LICENSE.txt](LICENSE.txt).
