# CTM Forge

Converts Java Edition connected-texture packs (OptiFine / Continuity "CTM", and Fusion) and
animated block textures into Bedrock packs for the **Connected Textures** LeviLauncher mod
(Minecraft 1.26.60 and 1.26.62). Browse Modrinth
natively, CurseForge and Planet Minecraft in the built-in browser, or open a .zip/.jar.

- `core/` - plain Kotlin: the converter (a line-for-line port of `../converter/ctm_convert.py`), a PNG
  reader/writer (so pixels match on every device), CTM tile logic for previews, and the
  Modrinth client.
- `app/` - the Android app (Jetpack Compose, Material 3 / Material You, immersive mode).
  The mod (`ConnectedTextures.levipack`) ships in `app/src/main/assets`; GitHub Actions
  replaces it with the one it builds from `../mod`.

## Build

Needs JDK 17+ and the Android SDK (platform 36, build-tools 36.0.0). Set `ANDROID_HOME`
or point `local.properties` at the SDK (`sdk.dir=...`), then:

    ./gradlew :app:assembleRelease

The APK lands in `app/build/outputs/apk/release/`.

### Signing

Release builds are signed with the key from `keystore.properties` (next to
`settings.gradle.kts`, with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`) or from
the environment variables `CTM_KEYSTORE_FILE`, `CTM_KEYSTORE_PASSWORD`, `CTM_KEY_ALIAS` and
`CTM_KEY_PASSWORD`. With neither, they're signed with the debug key: that APK installs, but
Android won't let it update an install signed with the real key.

Never commit the keystore or `keystore.properties` (they're in `.gitignore`). Keep a backup:
Android only installs updates signed with the same key.

## Updates

CTM Forge reads this repository's latest GitHub release (drafts and pre-releases are skipped)
when it opens, at most every 6 hours (Settings → Updates → Check automatically), or when you
tap **Check for updates**. CI attaches the two files it looks for to every `v*` tag:

- `CTM-Forge-<versionCode>.apk`: an update when `<versionCode>` is higher than the installed
  build's, so **bump `versionCode` in `app/build.gradle.kts` before tagging a release**. The
  download is checked against GitHub's SHA-256, its package name, its version and its signing
  key before it installs.
- `ConnectedTextures.levipack`: downloaded right away when its manifest `version` is newer than
  the mod the app has. The app then offers it everywhere it offers the bundled mod.
  LeviLauncher still imports it: tap **Install mod** on the card in the Library or in Settings.

App updates install through [Shizuku](https://shizuku.rikka.app/) when it's running and CTM
Forge is allowed in it (Settings → Updates → Install with Shizuku): no system prompt, and the
app closes and opens again by itself. After an automatic check they install on their own
(they wait if a conversion is running). Without Shizuku, **Update** uses Android's installer,
which asks first; on Android 12+ later updates can skip that prompt once CTM Forge has installed
itself.

## Tests

    CTM_GOLDEN=/path/to/golden ./gradlew :core:test           # Kotlin vs Python converter, file by file
    CTM_NET=1 ./gradlew :core:test --tests '*NetTest*'          # live Modrinth search -> download -> convert
    CTM_GOLDEN=/path/to/golden ./gradlew :app:testDebugUnitTest # Robolectric walkthrough + screenshots

`CTM_GOLDEN` is a folder with `packs/<unpacked Java packs>` and `proj/out/` (the Python
converter's output). The packs aren't included: they belong to their authors. Without it
those tests skip. Screenshots go to `app/build/screens/`.

Credits: hook signatures in the mod adapted from BedrockTools by RadiantByte (MIT).
