# Android client (v0.1)

Gradle project for FCP Launcher on Android. Account and download code lives in the JVM module `core/` (repository root) so a later desktop shell can reuse it. The UI is `android/`.

v0.1 follows the same account and download behavior as [HMCL](https://github.com/HMCL-dev/HMCL) and [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher): offline names, authlib-injector / Yggdrasil, and BMCLAPI-first files. It does not vendor the FCL native tree (Amethyst / Pojav). Launching the game process is a later change. No Plain Craft Launcher source is included.

## What works

- Offline account: local player name and the Minecraft `OfflinePlayer:` UUID. Nothing is sent to Mojang.
- LittleSkin login. The API root is prefilled from the [LittleSkin manual](https://manual.littleskin.cn/yggdrasil/) as `https://littleskin.cn/api/yggdrasil`. Passwords are sent only to that server and are not saved; the access token and client token are.
- Advanced custom Yggdrasil server, including authlib-injector API Location Indication (`X-Authlib-Injector-API-Location`), which is how FCL accepts a short host such as `littleskin.cn`. Labeled as third-party, not Microsoft.
- Version list for releases, snapshots (hidden until chosen), and old alpha/beta. Historical ids such as `1.0`, `1.7.10`, and current releases come from the manifest.
- Install layout matches a normal Minecraft directory under the app's files dir: `versions/<id>/<id>.json`, `<id>.jar`, `libraries/`, `assets/indexes/`, `assets/objects/`.
- Default order is BMCLAPI2, then the original Mojang URL. SHA-1 is checked when the manifest or version JSON provides one.

## What is deferred

- Official Microsoft login. v0.1 does not register an Azure app and does not embed another launcher's client id.
- Starting the JVM / rendering a world (FCL native backend).
- Multiplayer (issue #4) and the desktop apps (issue #5).

## Download sources

| Order | Source | Why |
|-------|--------|-----|
| 1 | [BMCLAPI2](https://bmclapi2.bangbang93.com) (`bmclapi2.bangbang93.com`) | Community mirror operated by bangbang93. HMCL and FCL use it as the China-friendly source for the version manifest, client, libraries, and assets. |
| 2 | Mojang official hosts | `piston-meta.mojang.com`, `piston-data.mojang.com`, `launchermeta.mojang.com`, `launcher.mojang.com`, `libraries.minecraft.net`, `resources.download.minecraft.net`. Used when BMCLAPI does not return a matching file. |

The About screen always credits BMCLAPI. Unknown mirrors are not queried.

## Build

Requirements: JDK 17+, Android SDK 35, Android Gradle Plugin via the wrapper.

```bash
export ANDROID_HOME="$HOME/android-sdk"   # or your SDK path
./gradlew :core:test
./gradlew :android:assembleDebug
```

Open the repository root in Android Studio (not only the `android/` folder) so the `:core` module resolves. The debug APK is `android/build/outputs/apk/debug/android-debug.apk`.

`:core:test` includes a live check that:

- the version manifest is requested from BMCLAPI2 first
- the list contains an old alpha, a snapshot, `1.0`, `1.7.10`, and `1.20.4`
- `1.7.10` and `1.20.4` install a client jar whose SHA-1 matches the version JSON, plus a couple of libraries and asset objects
- LittleSkin's public metadata names the server LittleSkin, including the ALI redirect from `https://littleskin.cn`

## Branding

Use `shared/Branding` — show `PRODUCT_NAME` everywhere; `PRODUCT_FULL_NAME` only as a hidden easter egg (tap the About title seven times); formal About stays `PRODUCT_NAME`.
