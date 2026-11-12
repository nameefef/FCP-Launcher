# Desktop client

Windows, macOS, and Linux shell for FCP Launcher. It calls `:core` for accounts, BMCLAPI downloads, and the launch command. The game is a separate process. Native libraries come from the version JSON (LWJGL classifiers for the current OS). The Android Fold Craft Launcher `.so` files are not used here.

Microsoft login is not included. There is no Mojang client id in this repo, and other launchers' Azure application ids are not embedded.

## UI

Compose Multiplatform Desktop. Chinese is the default copy. English is used when `LC_ALL` or `LANG` names English (`en_US`, `en_GB`, …). `C`, `POSIX`, and `C.UTF-8` are not a language — the JVM reports them as English, and the UI stays Chinese anyway. A blank Chinese string falls back to English. The green bar and 版本 / 账号 / 关于 layout follow the Android app. 设置 adds the game directory, `-Xmx`, an optional Java path, and the download source (BMCLAPI first, or Mojang only).

Java is chosen from the version JSON `javaVersion.majorVersion` (8 when the field is absent and the id is 1.16 or older, 17 for 1.18–1.20.4, 21 for 1.20.5 and newer). A path set in 设置 is preferred when its major matches. If nothing matches, the dialog explains which Java is required and which installs were found.

## Run

```bash
./gradlew :desktop:run
```

Accounts and settings are stored in `~/.fcp-launcher/`. The default game directory is `~/.fcp-launcher/minecraft`.

## Packages

`desktop/build.gradle.kts` configures jpackage formats for Linux (deb, rpm, AppImage), Windows (msi, exe), and macOS (dmg). jpackage rejects a package version whose major is 0, so the installer version is `1.0.0`. The in-app version string stays `0.3.0`.

On Linux:

```bash
./gradlew :desktop:packageUberJarForCurrentOS
./gradlew :desktop:createDistributable
```

`packageUberJarForCurrentOS` writes `desktop/build/compose/jars/fcp-launcher-linux-x64-1.0.0.jar`. `createDistributable` writes an app image under `desktop/build/compose/binaries/main/app`. `packageDeb`, `packageMsi`, `packageExe`, and `packageDmg` exist for the other formats; msi/exe/dmg have to be built on those operating systems.

The Android module still needs an Android SDK to configure. Desktop and `:core` tasks can be built with `--configure-on-demand` when the SDK is not installed.
