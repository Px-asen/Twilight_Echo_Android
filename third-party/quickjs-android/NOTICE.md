# Cash QuickJS 0.9.2 — Android source build

Twilight Echo builds the existing Cash QuickJS binding from source so that
`libquickjs.so` supports 16 KiB memory pages. Both the Music Core host and the
isolated plugin host use this module; the Maven prebuilt is no longer packaged.

## Pinned source

- Original project: <https://github.com/cashapp/quickjs-java> (now redirects to
  `cashapp/zipline`).
- Release commit: [`a738129cc4aa99206c00ae49e9c5b15cb58ad880`](https://github.com/cashapp/zipline/tree/a738129cc4aa99206c00ae49e9c5b15cb58ad880),
  “Prepare version 0.9.2”, 2021-08-04.
- Binding version: `0.9.2`; bundled QuickJS engine: `2021-03-27`.
- `upstream/` contains the Java, JNI and QuickJS sources required by the Android
  library, their original build files, license and provenance files.
  The three Java files are byte-for-byte identical to the Maven 0.9.2 sources.
- Do not use the current `0.9.2` Git tag: it identifies a later Zipline release,
  not the QuickJS artifact published to Maven in August 2021.

## Local build integration

The adjacent Gradle and CMake files replace the old AGP 4 build integration with
this project's AGP 8 / NDK r27 toolchain. They retain `app.cash.quickjs`, the
`quickjs` library name, C99 / C++11, static libc++, `CONFIG_VERSION`, the release
optimization profile and the original non-BIGNUM configuration. They enable
flexible page sizes and link with `-z max-page-size=16384`. The Java/JNI behavior,
threading contract and engine bytecode format are unchanged. The module's
minimum Android API follows the app's API 26 minimum.

The only local source patch adds the missing `<functional>` include to
`upstream/quickjs/common/native/Context.h`. That header declares `std::function`
and previously relied on an indirect libc++ include that NDK r27 no longer
provides. No engine or binding algorithm is changed.

Build all four ABIs through the normal application build:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
python tools/check_native_alignment.py app/build/outputs/apk/debug/app-debug.apk
```

The check inspects every packaged native ELF and the ZIP offsets of uncompressed
native libraries. Target-device execution on 4 KiB and 16 KiB systems remains
part of application runtime acceptance.

## Licenses

The Java/JNI binding is Copyright Square, Inc., under Apache License 2.0
(`upstream/LICENSE`). QuickJS is Copyright Fabrice Bellard and Charlie Gordon,
under the MIT license (`QUICKJS-LICENSE` and each original source header).
Both licenses and this notice are included in APK assets under
`licenses/quickjs-0.9.2/`.
