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
`Android/data/is.xyz.mpv/files/Music/MorningShow/<session>/`.

Recording stays local; no account, remote service, camera, or upload is required.
Android shows an ongoing foreground recording notification with a Stop action.

## Timeline data

Every marker includes the device's monotonic
`elapsed_realtime_nanos` clock and UTC wall time in milliseconds.
**These are not yet a verified common clock across phones.**
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
