# Morning Show · audio-first mpv Android fork

This branch adds a separate **Morning Show Recorder** launcher activity to
mpv-android. The original player remains available. No video capture is active.

## Capture on each phone

1. Install the fork on each consenting Android device.
2. Open **Morning Show Recorder**, enter the identical morning session ID.
3. Select **Friso** on the first phone, **Vanessa** on the other.
4. Grant microphone permission and press **START MICROPHONE**.
5. Press **SYNC MARKER / CLAP** when announcing a scene boundary.
6. Press **STOP AND SAVE** to finalize the M4A recording.

Each phone stores an independent AAC mono 48 kHz, 128 kbps M4A file,
a JSONL event log and a session JSON sidecar at:
`Android/data/de.torsionfield.morningmpv/files/Music/MorningShow/<session>/`.

Recording stays local; no account, remote service, camera, or upload is required.
Android shows an ongoing foreground recording notification with a Stop action.

## Timeline data

Every marker includes the device's monotonic
`elapsed_realtime_nanos` clock and UTC wall time in milliseconds.
**These are not yet a verified common clock across phones.**
The Chromebook mpv player additionally writes 2 Hz playback metadata into a
`player_*.events.jsonl` sidecar under `Android/data/de.torsionfield.morningmpv/files/Documents/MorningShow/<session>/`, including media path, current position,
pause state, playback rate, and seek/discontinuity observations.
The next milestone will add a Chromebook master clock, bidirectional
clock-offset measurements, and clip-position anchors. Manual markers alone
do not guarantee frame-accurate synchronization across devices.

## Optional video later

The JSON metadata already declares `capture_video: false` and
`video_adapter: "none"`. A later opt-in VDO.Ninja source may be added
for webcam streaming, face/body tracking and Genesis 9 pose inference.
That adapter must not interrupt local microphone recording and must request
separate camera permission. No video or pose estimation is enabled now.

## Build and verification

This fork requires mpv's native libraries before it can launch.
Follow upstream `buildscripts/README.md` on Linux/macOS for those,
then build the Android Gradle application.

The first acceptance test is an independently recorded M4A and matching
JSONL on one real device; the two-phone synchronized test follows.

## Offline two-phone audio alignment

A host-side experiment is available at `scripts/align_audio.py`.
It decodes two exported M4A/WAV recordings with FFmpeg and estimates
their relative offset from shared ambient sound or a deliberate clap:

```sh
python3 scripts/align_audio.py Friso.m4a Vanessa.m4a --seconds 45 \
  --max-lag 15 --json-output session_alignment.json
python3 scripts/test_align_audio.py
```

Requires NumPy and FFmpeg on the host. A synthetic 0.700-second offset
is detected in its deterministic test. Low acoustic correlation is
flagged for review; do not assume reliable synchronization without
a common sound or verified clock exchange. This is audio-only and does
not infer body position, emotion or true device clock offsets.

## Central i9ES recorder (first connected release)

The central hub source is `central/server.py` and its persistent private
SQLite and recordings storage is `central/state/` (excluded from git).
The per-machine bearer key is `central/state/token` (mode 0600).
The user-systemd service `central/morning-show-central.service` runs Gunicorn
on loopback port 18767; Tailscale Serve independently publishes it at
`https://i9es-home.rattlesnake-shiner.ts.net:19884` (tailnet only).

The browser studio is at `/studio`. Enter the local recorder token into
the Studio once per browser profile. Choose a queued fragment and press Play.
The player reports the exact YouTube playback position twice per second
and responds to pause, seek, and clip transitions.

Chrome's unpacked MV3 extension lives in `extension/`. On YouTube, capture
start/end positions and queue selected fragments in the hub. Install unpacked
on ChromeOS using Developer Mode; ChromeOS must join the same tailnet.

Android now takes the hub URL and pairing token in the recorder screen.
During recording it samples the authoritative hub playback state at ~1 Hz,
saving send/receive monotonic timestamps plus the server's position to the
local JSONL. After stop it attempts to upload M4A, JSONL and session metadata.
When the hub is unavailable the original local recording is retained.
Automatic retry after app termination remains an unimplemented improvement.

A development-only USB test may use `adb reverse tcp:18767 tcp:18767`
and the recorder URL `http://127.0.0.1:18767`. That loopback-only HTTP
exception exists for ADB; normal service should use HTTPS over Tailscale.
The phone's session ID must match `morning-YYYYMMDD` from the browser studio.

Only original human commentary audio is recorded. VDO.Ninja and subsequent
Genesis 9 pose estimation remain separately permissioned future adapters.

## Downloadable builds

The hub also serves the unpacked Chrome extension archive as
`/extension.zip` and the latest locally signed Android APK as `/app.apk`.
Those routes are reachable only from the Tailscale-connected network.
The signing key remains on i9ES under gitignored `central/state/signing/`.
When the phone reconnects, an update can use `adb install -r` with this
same signing key without removing previous recordings.
The recorder screen includes **RESEND LAST TO i9ES** for interrupted uploads.

Huawei USB testing used a loopback hub URL; later builds automatically show
the secure i9ES HTTPS address instead when the saved URL equals that one-time
debug loopback address. Previously recorded files remain untouched.
