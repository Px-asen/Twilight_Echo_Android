<h1 align="center">Twilight Echo for Android</h1>

<p align="center">
  <strong>Carry your favorite music in your pocket, and connect to your desktop library at home.</strong><br />
  An open-source Android music player unifying local collections, online streaming, and Desktop Core LAN bridge.<br />
  From ambient artwork lyrics and theme customization to a shared C++ native audio engine and 10-band EQ.
</p>

<p align="center">
  <a href="https://github.com/Px-asen/Twilight_Echo_Android/releases"><img alt="Releases" src="https://img.shields.io/github/v/release/Px-asen/Twilight_Echo_Android?display_name=tag&style=flat-square&color=8b5cf6" /></a>
  <img alt="Android 8.0+ (API 26+)" src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3ddc84?style=flat-square" />
  <img alt="Native Audio: FFmpeg + C++ DSP + AAudio" src="https://img.shields.io/badge/Audio-FFmpeg%20%2B%20C%2B%2B%20DSP%20%2B%20AAudio-8b5cf6?style=flat-square" />
  <a href="https://github.com/Px-asen/Twilight_Echo"><img alt="Twilight Echo Desktop" src="https://img.shields.io/badge/Desktop%20Core-Twilight%20Echo-2563eb?style=flat-square" /></a>
  <a href="./LICENSE"><img alt="Apache 2.0 License" src="https://img.shields.io/badge/license-Apache--2.0-64748b?style=flat-square" /></a>
</p>

<p align="center">
  <a href="./README.md"><strong>简体中文</strong></a>
  · <a href="https://github.com/Px-asen/Twilight_Echo_Android/releases">Download APK</a>
  · <a href="#quick-start">Quick Start</a>
  · <a href="#features-at-a-glance">Features</a>
  · <a href="#desktop-core-lan-bridge">Desktop Core Bridge</a>
  · <a href="#supported-audio-formats">Audio Formats</a>
  · <a href="#frequently-asked-questions">FAQ</a>
  · <a href="https://github.com/Px-asen/Twilight_Echo_Android/issues">Issues</a>
</p>

## Highlights

- **Shared Core & Native Audio Engine**: Runs the exact same portable Music Core (on QuickJS) and C++ audio engine (`FFmpeg → C++ DSP → AAudio`) as the [Twilight Echo Desktop](https://github.com/Px-asen/Twilight_Echo) player. Local files, online streams, and desktop LAN streams all travel through the same native decoding and DSP pipeline.
- **Roon-Style Desktop Core LAN Bridge**: Automatically discovers computers running Twilight Echo on your home Wi-Fi and pairs with a single tap of approval on the desktop—no IP addresses or PIN codes required. Browse your desktop library, artist bios, and high-res artwork on your phone, and freely route audio to **This Phone** (authenticated FLAC/MP3 streaming) or **Computer** (remote playback control).
- **Immersive Artwork Stage & Wave-Spring Lyrics**: Built around a refined neutral dark palette (`#1C1C1E`) with Monet artwork accent extraction, multi-quadrant rotating ambient artwork backgrounds, line-by-line focus scaling, depth-of-field blur (Android 12+), and under-damped wave-spring lyric transitions.
- **Built-in Streaming & Isolated Plugin Ecosystem**: Access NetEase Cloud Music daily recommendations, Personal FM, Personal Radar, cloud favorites, playlists, albums, artists, and listening charts right from Home and the Streaming Library. Install mobile `.tep` provider plugins that execute safely inside an isolated-UID sandboxed service process.
- **10-Band EQ, AutoEQ Import & Transparent Signal Path**: Shape your sound with a 10-band graphic EQ, 32-band parametric EQ support, preamp headroom, EqualizerAPO / AutoEQ text import & export, JSON scene packages, and per-device presets—backed by an honest signal-path inspector showing source specs, active DSP, engine request, AAudio stream, and hardware format.

## Download & Installation

**[Download Latest APK from Releases →](https://github.com/Px-asen/Twilight_Echo_Android/releases)**

1. Download the latest `.apk` from [Releases](https://github.com/Px-asen/Twilight_Echo_Android/releases) (universal builds include `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86` ABIs).
2. **System Requirements**: Android 8.0 (API 26) or later. Android 12 (API 31)+ enables progressive lyric depth blur; Android 13 (API 33)+ supports runtime media notification permission.
3. On first launch:
   - Tap **Scan Local Music** on the Home or Library screen and grant audio/storage access to index your on-device tracks. The app only displays your real library—no bundled demo tracks are forced into your queue.
   - On Android 13+, grant notification permission when prompted so the system shade and lock screen can display media playback controls and high-resolution album artwork (you can also re-enable this anytime under **Settings → Playback Notification**).

## Quick Start

**Listen to Local Collections** — Once scanned, browse your music by Songs, Albums, Artists, or Playlists. Every playlist (including *Liked Songs*) supports instant in-playlist filtering by title, artist, or album and remembers its own sort order. `.cue` sheets are automatically split into virtual tracks, and you can import bilingual `.lrc` files directly from the player's **More Actions → Import LRC Lyrics** menu.

**Discover Online Streaming** — No local files required to get started. Enable the built-in NetEase Cloud Music provider from the Home card or **Settings → Plugins & Sources** and sign in via QR code or account credentials:
- **Home** automatically loads Daily Recommendations, Personal FM, and Personal Radar;
- **Library → Streaming** organizes your liked tracks, created/collected playlists, saved albums, followed artists, recent plays, and weekly listening rankings;
- **Search → NetEase Cloud** searches across songs, albums, artists, and playlists;
- **Settings → Streaming Quality** lets you choose Auto, Hi-Res Lossless, Lossless, Extreme, or Standard quality. Additional mobile-compatible `.tep` providers can be installed from **Settings → Plugins & Sources**.

**Connect to Your Desktop Core** — With both your phone and computer on the same LAN and *LAN Remote Control* enabled on the desktop app, open **Settings → Desktop Core** on your phone. Tap your discovered computer and click **Approve** on your desktop screen. Once trusted, reconnect with a single tap anytime and switch **Audio Output** between **This Phone** and **Computer**.

**Customize Your Look & Lyrics** — Open **Settings → Appearance** to personalize:
- **Accent Color**: Automatic Monet extraction from album artwork, or custom presets, hex codes, and HSL sliders;
- **Background**: Switch between ambient flowing artwork, custom gallery photos, or solid dark/light backgrounds (light backgrounds automatically switch to dark typography and system bar icons);
- **UI Scale & Opacity**: App-independent DPI scaling (80%–125%) and container surface opacity (20%–100%).
Open **Settings → Lyrics** for a live preview where you can tune lyric font size (22–46), font weight, line spacing, depth blur strength (0–8), and bilingual translation visibility.

**Tune Audio & Inspect the Signal Path** — Open **Settings → Audio & DSP** to adjust the 10-band EQ and preamp gain, import/export EqualizerAPO or AutoEQ text profiles and JSON scene packages, or configure output device presets. While playing, expand the signal path cards (**Source → Audio Processing → Playback Output**) to verify decoded format, active DSP stages, engine request, actual AAudio stream, and hardware sample rate.

## Features at a Glance

<table>
  <tr>
    <td width="50%" valign="top">
      <h3>🎵 Local Library & CUE Splitting</h3>
      <p>Scan MediaStore and custom music folders; browse by tracks, albums, artists, and playlists. Supports automatic CUE sheet virtual tracks, per-playlist search and persistent sorting, accurate audio header probing, and playback bookmarks.</p>
    </td>
    <td width="50%" valign="top">
      <h3>🔎 Streaming & Sandboxed Plugins</h3>
      <p>Ships with the desktop-shared NetEase Cloud Music provider for daily picks, Personal FM, radar playlists, cloud likes, and charts. Runs third-party mobile <code>.tep</code> extensions inside an isolated-UID QuickJS service process with strict permission and memory bounds.</p>
    </td>
  </tr>
  <tr>
    <td valign="top">
      <h3>🖥️ Desktop Core LAN Bridge</h3>
      <p>Roon-inspired separation of <em>Core</em>, <em>Control</em>, and <em>Output</em>. Zero-PIN mDNS discovery and desktop approval let you browse desktop albums, artist bios, and multi-version releases, then stream FLAC/MP3 to your phone or control desktop playback.</p>
    </td>
    <td valign="top">
      <h3>✨ Ambient Artwork & Wave-Spring Lyrics</h3>
      <p>Multi-quadrant feathered artwork motion backgrounds, synchronized LRC/YRC lyrics with bilingual translations, active-line scaling, Android 12+ progressive blur, and wave-spring line advances. Auto-hides controls for distraction-free full-screen lyrics.</p>
    </td>
  </tr>
  <tr>
    <td valign="top">
      <h3>🎧 Shared Native Audio & 10-Band EQ</h3>
      <p>Local files, SAF content URIs, online providers, and desktop streams all run through <code>FFmpeg → C++ DSP → AAudio</code>. Includes 10-band EQ, 32-band PEQ engine support, AutoEQ import/export, device presets, and evidence-based signal path diagnostics.</p>
    </td>
    <td valign="top">
      <h3>🎨 Tailored Appearance & Typography</h3>
      <p>Restrained Apple-inspired neutral dark surfaces (<code>#1C1C1E</code>) paired with artwork-derived accents. Customize accent colors, photo or solid backgrounds, app-only DPI scaling (80%–125%), container opacity, and live-previewed lyric typography.</p>
    </td>
  </tr>
  <tr>
    <td valign="top">
      <h3>🔔 Media3 Session & Safety Brake</h3>
      <p>Projects playback state and 1024px artwork to AndroidX Media3 notifications and lock-screen cards. Immediately pauses on headphone or USB DAC unplug, preserves user volume across transient audio-focus ducking, and respects manual pauses.</p>
    </td>
    <td valign="top">
      <h3>💾 Seamless Session Recovery</h3>
      <p>Automatically persists your active track, queue order, playback position (every 5s and on explicit pause/seek), user volume, repeat/shuffle mode, and EQ/DSP settings—restoring your exact session in a paused state on next launch.</p>
    </td>
  </tr>
</table>

## Desktop Core LAN Bridge

Twilight Echo for Android decouples **Library Source** (which catalog you are browsing) from **Audio Output** (which device produces sound):

| Library Source | Audio Output | Behavior |
| -------------- | ------------ | -------- |
| **Desktop Library** | **This Phone** | Streams authenticated original files (FLAC / MP3) from your computer over LAN and decodes them on your phone via `FFmpeg → C++ DSP → AAudio` using the phone's own queue |
| **Desktop Library** | **Computer** | Turns your phone into a live remote controller for the desktop Twilight Echo player, using the computer's queue and audio output device |
| **Local Library** | **This Phone** | Plays audio files stored on your Android device completely offline |

- **One-Tap Reconnection**: After you approve the phone once on your desktop, credentials are bound to the desktop's persistent `serverId`. Even if your computer's LAN IP changes, your phone updates the endpoint automatically via mDNS and reconnects with one tap.
- **Independent Queues Without Interruption**: Connecting to a desktop library or toggling the output target between *This Phone* and *Computer* never pauses active audio on either device or overwrites either queue.
- **Live Metadata Revisions**: Edits made on your desktop (artwork updates, artist bios, catalog changes) stream over SSE revisions and refresh open pages on your phone automatically.

## Supported Audio Formats

| Category | Supported Formats & Capabilities |
| -------- | -------------------------------- |
| **Lossless & Hi-Res** | FLAC, ALAC (`.m4a`), WAV / PCM, APE, WavPack (`.wv`) |
| **Lossy Audio** | MP3, AAC (`.m4a` / `.aac`), OGG Vorbis, Opus |
| **Cue Sheets & Lyrics** | `.cue` virtual track splitting, local `.lrc` bilingual synchronized lyrics, online YRC / LRC lyrics |
| **Audio Output** | Native Android AAudio output (Shared / Exclusive mode requests) with real-time routing tracking across speaker, wired headset, Bluetooth, and USB DAC |

> [!NOTE]
> On Android, audio currently outputs PCM through the shared `FFmpeg → C++ DSP → AAudio` pipeline, reporting verified *Engine Request*, *AAudio Stream*, and *System Hardware* formats separately. Dedicated USB Exclusive drivers, DoP / Native DSD output, and the full AutoMix UI are planned in upcoming milestones—see [POWERAMP_HIFI_COMPARISON.md](./POWERAMP_HIFI_COMPARISON.md).

## Frequently Asked Questions

<details>
<summary>Do I need an internet connection or account to play local music on my phone?</summary>

No. Scanning and playing local audio files, splitting CUE sheets, importing local LRC lyrics, and customizing EQ and appearance work 100% offline without any account. Network access is only used when browsing online streaming providers, refreshing the plugin catalog, or connecting to a Desktop Core over LAN.

</details>

<details>
<summary>How do I connect to Twilight Echo on my computer? What if my computer isn't discovered?</summary>

1. Connect both your phone and computer to the **same trusted LAN / Wi-Fi**, and enable **LAN Remote Control** in the desktop app's Settings.
2. Open **Settings → Desktop Core** on your phone to discover the computer automatically via mDNS (`UDP 5353`, no location permission required).
3. If guest Wi-Fi, AP isolation, VPNs, or firewall rules block multicast discovery, expand **Can't find your computer? Connect manually** and enter the desktop's LAN address (e.g. `192.168.1.20:18492`) along with the 6-digit pairing code shown on your desktop.

</details>

<details>
<summary>Can I install any desktop Twilight Echo plugin on Android?</summary>

Both platforms share the `plugins.json` marketplace index and `.tep` package format, but Android executes plugins inside a mobile QuickJS sandbox (`:plugin-host` with an isolated process UID). A plugin must declare an `androidRuntime` entry in its manifest to run on Android. Compatible providers can be installed and enabled under **Settings → Plugins & Sources**; desktop-only plugins clearly display why they are unavailable on mobile.

</details>

<details>
<summary>Why isn't the media notification or lock-screen artwork showing up?</summary>

Make sure notification permission is enabled under **Settings → Playback Notification** (required on Android 13+). Once enabled, Twilight Echo publishes 1024px artwork and transport controls through AndroidX `MediaSession`. If the notification appears in the shade but not on the lock screen, check your device's system Lock Screen Notification privacy settings and media card toggle.

</details>

<details>
<summary>What happens when I unplug headphones, receive a phone call, or hear navigation prompts?</summary>

- **Headphone / USB DAC Unplug**: Disconnecting the active output device immediately triggers the platform safety brake and pauses native audio so music never blasts unexpectedly from the phone speaker. Disconnecting an idle device leaves playback untouched.
- **Navigation / Notification Ducking**: Transient focus ducking applies an independent attenuation multiplier and restores your exact user volume afterward.
- **Calls & Other Media Apps**: If Twilight Echo was actively playing before a transient interruption, playback resumes when focus returns. If you paused manually, it stays paused.

</details>

## Feedback & Community

Found a bug or have a feature request? Please [open an Issue](https://github.com/Px-asen/Twilight_Echo_Android/issues/new) in this repository (or in the [Desktop main repository](https://github.com/Px-asen/Twilight_Echo/issues)):

- **Bug Reports**: Include your Android version, phone model, audio output device (speaker / wired / Bluetooth / USB DAC), observed behavior, and reproduction steps. For audio issues, attach a screenshot of the expanded Signal Path cards under **Settings → Audio & DSP**.
- **Feature Requests & Questions**: Share your listening workflow and what would make the mobile experience better.

**Official QQ Group: `1093775290`** — Discuss desktop and mobile usage, join preview testing, and get streaming plugin updates.

##### **If you enjoy using Twilight Echo, consider giving the project a Star, sharing it with friends, or supporting development on [Afdian](https://afdian.com/a/pxasen). Developers interested in contributing are welcome to reach out via QQ `3248974986` to join our internal developer group!**

## Documentation & Building from Source

Detailed design notes, protocol specifications, and verification guides are available in this repository:

- [Phone UI & Immersive Lyrics (`UI_REDESIGN.md`)](./UI_REDESIGN.md) · [Plugins & Streaming Providers (`PLUGINS.md`)](./PLUGINS.md) · [Desktop Core & LAN Streaming (`DESKTOP_CORE.md`)](./DESKTOP_CORE.md) · [Native Audio & FFmpeg Build (`NATIVE_AUDIO.md`)](./NATIVE_AUDIO.md) · [HiFi Roadmap (`POWERAMP_HIFI_COMPARISON.md`)](./POWERAMP_HIFI_COMPARISON.md)

<details>
<summary>Module Layout & Build Instructions (For Developers)</summary>

### Multi-Module Layout

```text
Twilight_Echo_Android/
├── app/                    # Application entry, MainActivity, ForegroundService & permissions
├── feature/player/         # Home, Library, Streaming, Immersive Player, Lyrics, Settings & Desktop Core screens
├── ui/                     # Compose theme, Monet palette, custom backgrounds, glass components & vector icons
├── core-client/            # Shared Core protocol DTOs, Desktop Library/Stream client, EQ/AutoEQ & preferences
├── runtime-host/           # Single-threaded QuickJS execution host & Core port bridge
├── playback-host/          # AndroidPlaybackHost singleton, AudioFocusCoordinator, mDNS discovery & session store
├── plugin-host/            # Isolated-UID sandboxed plugin service, mobile plugin repository & NCM gateway
├── media-session/          # AndroidX Media3 MediaSessionService & system artwork projection
├── adapters/               # MediaStore/folder scanners, audio device routing, SAF & network adapters
└── native-integration/     # FFmpeg + C++ DSP + AAudio JNI bridge & seekable data sources
```

### Build Commands

Requirements: **JDK 17+** (JDK 21 recommended), **Android SDK 35**, and **Android NDK r27+** (when building native FFmpeg/JNI).

1. **Build Android FFmpeg libraries** (run once from the sibling `Twilight_Echo` desktop checkout):
   ```powershell
   $env:ANDROID_NDK_HOME='path/to/your/ndk'
   bash ../Twilight_Echo/sdk/android/ffmpeg/build-ffmpeg.sh .gradle/ffmpeg/7.1.2
   ```
2. **Sync Upstream Portable Core & Mobile Plugins** (after updating shared sources in `Twilight_Echo`):
   ```powershell
   # In ../Twilight_Echo:
   pnpm run build:portable-core
   node scripts/build-android-plugins.mjs

   # In Twilight_Echo_Android:
   .\gradlew.bat syncPortableCore syncAndroidPlugins
   ```
3. **Verify Core & Assemble Debug APK**:
   ```powershell
   .\gradlew.bat verifyPortableCore testDebugUnitTest :app:assembleDebug
   ```
   Output APK: `app/build/outputs/apk/debug/app-debug.apk`.

</details>

## License

Twilight Echo for Android is open-sourced under the [Apache License 2.0](./LICENSE). Bundled FFmpeg decoding libraries are licensed under [LGPL v2.1](./native-integration/src/main/assets/licenses/ffmpeg-7.1.2/COPYING.LGPLv2.1) (see [`NOTICE.txt`](./native-integration/src/main/assets/licenses/ffmpeg-7.1.2/NOTICE.txt)), and Material Symbols Rounded vector icons are licensed under [Apache License 2.0](./ui/src/main/assets/licenses/material-symbols-rounded.txt). Third-party dependencies, plugins, and media content remain subject to their respective licenses and terms of service.

For copyright or infringement inquiries, please contact Pxasen@outlook.com.
