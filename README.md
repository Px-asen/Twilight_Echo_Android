# Twilight Echo for Android (`Twilight_Echo_Android`)

Standalone native Android application repository for **Twilight Echo**, built with **Kotlin 2.0 + Jetpack Compose**, **AndroidX Media3**, **Embedded QuickJS Runtime**, and **C++ Audio Core (`twilight_audio_engine.h` + Oboe)**.

> Note: Staged inside `sdk/android-app` of the upstream workspace; designed to operate either at `sdk/android-app` or as the standalone sibling repository `../Twilight_Echo_Android`.

## Architecture Overview

Aligned with the cross-platform architecture specification (`docs/architecture/Twilight_Echo_Core_Architecture_and_Migration_Plan.md` Sections 8, 9, 10, 11, 17):

- **Shared Music Core (TypeScript -> JS Bundle)**: Owns playback session intent, business queue order, shuffle/repeat rules, optimistic concurrency revisions (`expectedRevision`), and recovery state. Executed on a dedicated single-threaded serial runtime (`CoreRuntimeThreadDispatcher`) inside `:runtime-host`.
- **Shared Audio Core (C++ / C ABI via JNI)**: Owns decoding, DSP, gapless preloading, AutoMix transitions, and real-time audio clock/output via Oboe (`:native-integration`).
- **Platform Safety Brake (`:playback-host`)**: `AndroidPlaybackHost` owns the singleton runtime and audio engine lifecycle. On `AUDIOFOCUS_LOSS`, `AUDIOFOCUS_LOSS_TRANSIENT`, or `ACTION_AUDIO_BECOMING_NOISY` (headphone unplug), the host immediately brakes native audio output and reports the platform interruption back to Music Core (Section 5.4).
- **Single Source of Truth Media3 Projection (`:media-session`)**: `CoreSimpleBasePlayer` projects `CoreSnapshot` and `CorePlaybackView` directly to Android `MediaSessionService` without maintaining a duplicate independent playback queue (Section 5.3).

## Multi-Module Layout (Section 11.8)

```text
Twilight_Echo_Android/
├── app/                    # Application entry, MainActivity, ForegroundService manifest declarations
├── feature/
│   └── player/             # NowPlayingScreen (Compose) & PlayerViewModel (UDF StateFlow)
├── ui/                     # Twilight Echo Compose theme, color tokens, typography, reusable components
├── core-client/            # Shared contract DTOs (CoreProtocol, CoreViews) & CoreClient with epoch/sequence reconciliation
├── runtime-host/           # Single-threaded JS execution context, Port interfaces, QuickJsRuntimeHost & CorePortBridge
├── playback-host/          # AndroidPlaybackHost singleton, AudioFocusCoordinator, Platform Safety Brake
├── media-session/          # TwilightMediaSessionService & CoreSimpleBasePlayer (Media3 projection)
├── adapters/               # AndroidClockAdapter, AndroidSessionStoreAdapter (atomic CAS), AndroidResourceAccessAdapter (SAF/MediaStore permission checks), AndroidNetworkAdapter
└── native-integration/     # TwilightAudioEngineJni, TwilightAudioEventListener, NativeAudioEngineAdapter (non-RT event dispatch)
```

## Upstream Core Synchronization

Upstream repository (`https://github.com/Px-asen/Twilight_Echo`) is locked via [`core-lock.json`](./core-lock.json) at commit `65688cccf919cbf36d900e92fd0a06eb4fb557c0`.

To synchronize the compiled portable Music Core bundle (`music-core.js` and `core-lock.json`) into `runtime-host/src/main/assets/core/`:

```bash
./gradlew syncPortableCore
```
