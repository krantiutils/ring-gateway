# Ring AI Android Gateway

Telephony gateway that turns a rooted Samsung Android phone into a programmable voice call + SMS endpoint. A WebSocket server sends commands (MAKE_CALL, PLAY_AUDIO, SEND_SMS, HANGUP) and the phone executes them over the cellular network.

The critical breakthrough: **audio injection into live cellular calls** via direct ALSA PCM writes through root access, bypassing Android's audio policy entirely.

## How Audio Injection Works

Android's audio policy blocks non-system apps from writing to the voice call uplink. Standard AudioTrack with `USAGE_VOICE_COMMUNICATION` or `STREAM_VOICE_CALL` silently fails on both Pixel and Samsung.

With root, we bypass this completely:

```
WAV file → tinyplay (root) → RDMA8 (ALSA PCM device 8)
    → SIFS5 (TX mixer) → FWDOUT_2 → cellular modem → other caller hears audio
```

On Samsung Exynos (ABOX audio subsystem), during a CP call the audio HAL automatically routes `SPUS OUT8 → SIFS5` (the voice call uplink mixer). Writing PCM to `pcmC0D8p` (card 0, device 8, playback) injects audio directly into the uplink. The `tinyplay` binary (cross-compiled from tinyalsa) does the ALSA writes.

Verified working on: Samsung Galaxy A51 (SM-A516N, Android 13, Exynos 8825, rooted).

## Architecture

```
┌──────────────────────────────────────────────────┐
│  Ring AI Backend (WebSocket Server)              │
│  Sends: MAKE_CALL, PLAY_AUDIO, SEND_SMS, HANGUP │
│  Receives: heartbeats, call events, responses    │
└────────────────────┬─────────────────────────────┘
                     │ WebSocket (JSON)
┌────────────────────▼─────────────────────────────┐
│  GatewayService (Android Foreground Service)     │
│  - OkHttp WebSocket client, auto-reconnect       │
│  - Command dispatcher                            │
│  - 15s heartbeat with status                     │
├──────────────────────────────────────────────────┤
│  CallManager         │  AudioInjector            │
│  - ACTION_CALL dial  │  - tinyplay via su        │
│  - TelephonyCallback │  - RDMA8 → SIFS5 → modem │
│  - State tracking    │  - WAV file playback      │
├──────────────────────┴───────────────────────────┤
│  Android OS (rooted) + Samsung ABOX audio HAL    │
└──────────────────────────────────────────────────┘
```

## Requirements

- Rooted Samsung phone with Exynos chipset (tested: A51, Android 13)
- USB debugging enabled
- SIM card with active cellular plan

### Build requirements (on dev machine)

- JDK 17
- Android SDK: platform 33, build-tools 34.0.0, platform-tools
- Android NDK 26 (for cross-compiling tinyplay)

## Project Structure

```
ring_gateway/
├── README.md
└── android-gateway/
    ├── gradlew, build.gradle.kts, settings.gradle.kts
    ├── app/
    │   ├── build.gradle.kts
    │   └── src/main/
    │       ├── AndroidManifest.xml
    │       ├── assets/
    │       │   ├── test_audio.wav          # 16kHz mono test tone
    │       │   └── tinyplay_arm64          # Cross-compiled ALSA player
    │       ├── java/com/ringai/gateway/
    │       │   ├── MainActivity.kt         # UI: manual test + gateway controls
    │       │   ├── CallManager.kt          # Dial + call state monitoring
    │       │   ├── AudioInjector.kt        # Root ALSA audio injection
    │       │   └── GatewayService.kt       # Foreground service + WebSocket
    │       └── res/
    │           ├── layout/activity_main.xml
    │           └── values/strings.xml
    └── gradle/
```

## Build & Install

```bash
cd ring_gateway/android-gateway

# Build debug APK
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

### Manual Test Mode

1. Open "Ring AI Gateway" app
2. Grant all permissions (Phone, Microphone, SMS)
3. Enter a phone number, press **Call**
4. Dialer opens — wait for the other person to answer
5. Swipe back to the app, press **Play Audio**
6. The other phone should hear the test tones

### Gateway Mode

1. Enter your WebSocket server URL (e.g., `ws://192.168.1.100:8080/gateway`)
2. Press **Connect**
3. The service runs in foreground and accepts commands from the server

## WebSocket Protocol

All messages are JSON. The gateway sends and receives on a single WebSocket connection.

### Commands (server → gateway)

```json
{"command": "MAKE_CALL", "id": "uuid-1", "number": "+9779812345678"}

{"command": "PLAY_AUDIO", "id": "uuid-2", "url": "https://example.com/prompt.wav"}
{"command": "PLAY_AUDIO", "id": "uuid-3", "path": "/data/local/tmp/audio.wav"}
{"command": "PLAY_AUDIO", "id": "uuid-4"}  // plays bundled test audio

{"command": "SEND_SMS", "id": "uuid-5", "number": "+9779812345678", "message": "Hello"}

{"command": "HANGUP", "id": "uuid-6"}

{"command": "PING", "id": "uuid-7"}
```

### Responses (gateway → server)

```json
{"type": "response", "id": "uuid-1", "result": "DIALING", "success": true}
{"type": "response", "id": "uuid-2", "result": "PLAYING", "success": true}
{"type": "response", "id": "uuid-2", "result": "AUDIO_COMPLETE", "success": true}
{"type": "response", "id": "uuid-5", "result": "SMS_SENT", "success": true}
{"type": "response", "id": "uuid-6", "result": "HANGUP_REQUESTED", "success": true,
 "message": "Audio stopped. Programmatic hangup requires InCallService registration."}
```

### Events (gateway → server)

```json
{"type": "event", "event": "CALL_CONNECTED", "status": "IN_CALL"}
{"type": "event", "event": "CALL_ENDED", "status": "CONNECTED"}
{"type": "event", "event": "CALL_ERROR", "status": "ERROR"}
```

### Heartbeat (gateway → server, every 15s)

```json
{"type": "heartbeat", "status": "CONNECTED", "audioPlaying": false}
```

### Gateway statuses

`IDLE` → `CONNECTING` → `CONNECTED` → `DIALING` → `IN_CALL` → `PLAYING_AUDIO` → back to `CONNECTED`

## Audio Format

WAV files for injection should be:
- **16kHz, mono, 16-bit signed PCM** (verified working)
- 48kHz stereo is rejected by the RDMA8 hardware params
- 32kHz mono may also work (untested)

## Known Limitations

1. **Programmatic hangup not possible** — Android requires `InCallService` registration (default dialer) to end calls programmatically. Calls must end naturally or from the dialer UI.

2. **Samsung Exynos only** — The RDMA8/SIFS5 routing is specific to Samsung's ABOX audio architecture. Other chipsets (Qualcomm, MediaTek) will have different PCM device mappings.

3. **Root required** — No way around this. Android's audio policy is enforced at the HAL level.

4. **Samsung call state quirk** — Samsung fires DIALING → IDLE → OFFHOOK during call setup. The app handles this by ignoring IDLE if OFFHOOK hasn't been reached yet.

5. **Audio plays locally too** — The tinyplay write goes to the ALSA device which feeds both the uplink and may produce local sound. For a phone-in-a-drawer gateway setup this doesn't matter.

## Discovery Notes

These findings from reverse-engineering the Samsung A51 audio path may be useful for other Exynos devices:

- Mixer paths config: `/vendor/etc/mixer_paths.xml`
- Audio policy: `/vendor/etc/audio_policy_configuration.xml`
- During CP call: `ABOX Audio Mode` = `IN_CALL`, `ABOX SPUS OUT8` = `SIFS5`
- Active PCM devices during call: `pcmC0D104p` (calliope_4), `pcmC0D113c` (calliope_13)
- RDMA8 routing is set up by HAL but device is closed — writing to it injects into TX
- `tinymix --card 0 contents` dumps all 1751 mixer controls
- Cross-compile tinyalsa with NDK: `aarch64-linux-android28-clang -I include -o tinyplay utils/tinyplay.c src/pcm.c src/pcm_hw.c src/pcm_plugin.c src/snd_card_plugin.c src/limits.c -ldl`
