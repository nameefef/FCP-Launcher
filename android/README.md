# Android client

Gradle project for FCP Launcher on Android. Account, download, and launch-command code lives in the JVM module `core/` (repository root) so a later desktop shell can reuse it. The UI is `android/`.

Behavior follows [HMCL](https://github.com/HMCL-dev/HMCL) and [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher): offline names, authlib-injector / Yggdrasil, BMCLAPI-first files, and a Minecraft Java command line. The FCL native tree (Amethyst / Pojav) is not vendored, so this APK cannot open a world until that runtime is placed in app storage. No Plain Craft Launcher source is included.

## What works

- Offline account: local player name and the Minecraft `OfflinePlayer:` UUID. Nothing is sent to Mojang.
- LittleSkin login. The API root is prefilled from the [LittleSkin manual](https://manual.littleskin.cn/yggdrasil/) as `https://littleskin.cn/api/yggdrasil`. Passwords are sent only to that server and are not saved; the access token and client token are.
- Advanced custom Yggdrasil server, including authlib-injector API Location Indication (`X-Authlib-Injector-API-Location`), which is how FCL accepts a short host such as `littleskin.cn`. Labeled as third-party, not Microsoft.
- Version list for releases, snapshots (hidden until chosen), and old alpha/beta. Historical ids such as `1.0`, `1.7.10`, and current releases come from the manifest.
- Install layout matches a normal Minecraft directory under the app's files dir: `versions/<id>/<id>.json`, `<id>.jar`, `libraries/`, `assets/indexes/`, `assets/objects/`.
- Default order is BMCLAPI2, then the original Mojang URL. SHA-1 is checked when the manifest or version JSON provides one.
- Launch. On an installed row, **启动** checks the selected account, client jar, libraries, asset index and objects, then builds the same kind of argument list HMCL uses (`inheritsFrom`, library rules with OS name `linux`, legacy `minecraftArguments` or modern `arguments`, offline `legacy` user type, Yggdrasil `mojang` user type). LittleSkin adds `-javaagent` for authlib-injector, downloading the jar from BMCLAPI and falling back to the GitHub release. When the Java binary and the native runtime are both present, the app execs that Java and keeps a foreground service while the process runs. Stdout, stderr, and the redacted command go to `logs/fcp-launch.log`.

## Runtime layout

Game files stay in `files/minecraft/`. The runtime is `files/runtime/`:

| Path | Role |
|------|------|
| `runtime/java-17/bin/java` (also `jre-17`, `Internal-17`, and the same for 8 and 21) | Android JRE for the major version in the version JSON. Older versions without `javaVersion` use Java 8. |
| `runtime/natives/` and the APK native library dir | `libglfw.so`, `libopenal.so`, and one renderer (`libgl4es.so`, `libvgpu.so`, `libvirglrenderer.so`, or `libOSMesa.so`). |
| `runtime/authlib-injector.jar` | Java agent for LittleSkin and other Yggdrasil accounts. |

If any of those are missing, launch stops before creating a process and the dialog lists what is missing. The same text is written to the log.

## What is deferred

- Shipping the Pojav / Fold Craft Launcher JRE and renderer inside the APK. Without them the launch path reports the gap; it does not enter a world.
- Official Microsoft login. This build does not register an Azure app and does not embed another launcher's client id.
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
- launch commands for a modern version and for 1.7.10, missing client / library / asset / JRE / native-runtime errors, and a stand-in Java process whose log redacts the access token

## Branding

Use `shared/Branding` — show `PRODUCT_NAME` everywhere; `PRODUCT_FULL_NAME` only as a hidden easter egg (tap the About title seven times); formal About stays `PRODUCT_NAME`.

Launcher art is the isometric grass block in `branding/` (`icon.svg`, `icon-512.png`, `ic_launcher.xml`). The Android drawable is a copy of `branding/ic_launcher.xml`.
