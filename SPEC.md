# Zerkalo — specification

Mirrors a phone's screen to a TV through an Android TV box over the home Wi-Fi. One app with two
roles: sender (phone) and receiver (TV box). No accounts, cloud or internet, the devices talk to
each other directly.

## Stack

- Kotlin + Jetpack Compose, one `:app` module, package and `applicationId` `xx.zerkalo`
- minSdk 28 / targetSdk 36, no native code
- One universal APK with no ABI splits: there is no native code, so separate builds are not needed
- Capture: `MediaProjection` + `VirtualDisplay`
- Encoding and decoding: hardware `MediaCodec`, H.264 and H.265
- Network: TCP for control, UDP for video and receiver discovery, a custom protocol
- Settings in `SharedPreferences`, no database
- `shared/updater` and `shared/about` are compiled in through `sourceSets`, as in the other projects
- UI languages: en, ru, uk. Name: Zerkalo / Зеркало / Дзеркало

## Target parameters

- Sender: Samsung A36. Receivers: Tox3 and Ugoos Cube X3 Pro
- Content: video and photos, smoothness takes priority over minimum latency
- Phone-screen-to-TV latency: goal ≤ 200 ms on 5 GHz Wi-Fi
- One sender per receiver at a time

## Out of scope for the first version

- Sound. A separate stage once video works
- Stream encryption. The PIN protects against an unwanted connection but not against interception
  inside the network: the video travels in the clear, and a four-digit PIN can be brute-forced
  from a recording of the exchange
- Receiver autostart. The receiver is always opened by hand from the TV box's launcher
- Automatic reconnection after a dropped connection
- Showing DRM video and `FLAG_SECURE` screens: the system supplies a black screen instead

## Roles

The role is stored in the settings and switched there. On first launch the value is chosen by
device: if `UiModeManager` reports a television or `FEATURE_LEANBACK` is present, receiver,
otherwise sender. Switching the role ends the current session.

Manifest as in PhotoBox: `LEANBACK_LAUNCHER`, `android:banner`, `touchscreen` and `leanback` with
`required="false"`. The whole receiver UI is operated with a remote.

## Usage flow

1. Zerkalo is opened on the TV box. The screen shows the receiver's name and a 4-digit PIN.
2. Zerkalo is opened on the phone, which lists the receivers it found. If the receiver is not in
   the list, its IP is entered by hand.
3. The PIN is entered on the first connection. After that the phone connects with one tap.
4. Android asks for permission to capture the screen. This happens at every mirroring start and
   cannot be avoided.
5. The TV shows the phone's screen with black bars at the sides or top, proportions kept.
6. Mirroring is stopped with the button in the app or in the notification, or by leaving the
   receiver. The receiver returns to its waiting screen.

## Network

| Port | Protocol | Purpose |
|---|---|---|
| 47700 | TCP | control, the receiver listens |
| 47701 | UDP | receiver announcements, the sender listens |
| 47702 | UDP | video, the receiver listens |

All numbers are big-endian. A string is a `u16` length and UTF-8 bytes. Protocol version
`PROTOCOL_VERSION = 1`.

### Receiver discovery

On its waiting screen the receiver broadcasts UDP every 2 seconds to its subnet's broadcast
address and to `255.255.255.255`:

```
magic "ZRKL" | u8 version | u8 type=0x01 | 16 bytes receiverId | u16 controlPort | string name
```

A receiver counts as gone after 7 seconds of silence. The sender holds a `MulticastLock` while the
receiver list is open, because without it some phones drop broadcast packets. If the router
isolates its clients, announcements will not arrive and entering the IP by hand will not help
either: a network without isolation is needed.

### Control channel

A TCP message is `u16 type | u32 length | payload`, length at most 64 KiB. The sender opens the
connection first.

| type | Direction | Message | Fields |
|---|---|---|---|
| 0x01 | S→R | HELLO | `u8 version`, 16 bytes `senderId`, `string name`, 16 bytes `nonceS`, `u8 mode` (0 PIN, 1 token) |
| 0x02 | R→S | CHALLENGE | 16 bytes `receiverId`, 16 bytes `nonceR` |
| 0x03 | S→R | AUTH | 32 bytes `proof` |
| 0x04 | R→S | AUTH_OK | `u8 codecs` (bit 0 H.264, bit 1 H.265) |
| 0x05 | S→R | CONFIG | `u16 configId`, `u8 codec`, `u16 width`, `u16 height`, CSD bytes |
| 0x06 | R→S | READY | `u16 configId` |
| 0x07 | R→S | KEYFRAME_REQUEST | none |
| 0x08 | both | PING | none |
| 0x09 | both | BYE | none |
| 0x0F | R→S | ERROR | `u8 code` (1 busy, 2 wrong PIN, 3 unknown token, 4 version) |

`codec` values: 0 is H.264, 1 is H.265.

### Pairing

```
pairKey = HMAC-SHA256(key = PIN, "zerkalo-pair" | nonceS | nonceR)
proof   = HMAC-SHA256(key = K,   "zerkalo-auth" | nonceS | nonceR)
```

`K` is `pairKey` in PIN mode and the saved token in token mode. After a correct PIN both sides
remember `pairKey` as the token: the sender by `receiverId`, the receiver by `senderId`. Neither
the PIN nor the token is sent over the network.

- A new PIN is generated at every receiver start and after 3 wrong attempts in a row.
- After 3 wrong attempts the receiver answers `ERROR 2` for 10 seconds without checking the PIN.
  Without this pause four digits can be tried over the network in minutes: changing the PIN does
  not slow guessing by itself.
- Answer `3` (unknown token) makes the sender forget the token and ask for the PIN.
- The receiver's settings have a list of remembered senders with removal.
- The receiver takes only one session and answers everyone else with `ERROR 1` at once.

A session lives as long as PINGs flow: once a second from each side, dropped after 4 seconds of
silence.

### Video

A datagram is at most 1400 bytes, so it is not fragmented at the IP level. The receiver accepts it
only from the address the control channel was opened from.

```
Video, S→R, 16-byte header:
u8 type=0x10 | u8 flags (bit 0 key frame) | u16 configId | u32 frameId |
u16 fragIndex | u16 fragCount | u32 ptsMs | payload up to 1384 bytes

NACK, R→S:
u8 type=0x11 | u8 count | u32 frameId | u16 fragIndex × count
```

A frame is one `MediaCodec` output buffer. CSD (`SPS`, `PPS`, `VPS`) goes only through CONFIG, that
is, over the reliable channel. Frames are sent in bursts of 32 packets with a 1 ms pause, otherwise
a large key frame overflows the access point's buffer.

**Losses.** A key frame takes hundreds of packets, and at 1% loss almost every such frame would
lose at least one packet. Requesting a new key frame would not help, as it would be lost the same
way. So selective repeat is needed:

- The sender keeps the packets of the last 32 frames.
- The receiver finds holes by number and sends a NACK. Requests repeat every 20 ms, at most 8 times
  per frame.
- If a frame is not complete by its deadline, the receiver throws it away, keeps the last good
  frame on screen and sends KEYFRAME_REQUEST, at most once per 500 ms.
- Until a key frame arrives the other frames are not decoded, so there are no artifacts on screen.

**Playout.** The receiver buffers frames for `PLAYOUT_DELAY_MS = 80`. The clock offset is the
minimum of `arrival time − ptsMs` over a sliding window, and a frame is shown through
`releaseOutputBuffer(index, renderTimeNs)`. A frame's assembly deadline is 250 ms from the arrival
of its first packet or from the detection of the gap.

## Capture and encoding (sender)

The start order matters, otherwise Android 14+ terminates the app with an error:

1. Connection and pairing, so a PIN error does not waste the capture permission.
2. The consent request: on API 34+ with `MediaProjectionConfig.createConfigForDefaultDisplay()`, so
   only the whole screen can be chosen.
3. `startForegroundService`, then `startForeground` with type `mediaProjection`.
4. `getMediaProjection`, registering `MediaProjection.Callback`, then `createVirtualDisplay`.

The token and `createVirtualDisplay` are single-use on Android 14+. On screen rotation the
`VirtualDisplay` is not recreated, it gets `resize` and `setSurface` from the new encoder.

The stream size is the phone's screen fitted into 1920×1080 with proportions kept and aligned to
the codec's requirements (`getWidthAlignment`, `getHeightAlignment`). The A36 in portrait gives
about 498×1080, which lowers the bit rate and stays within the decoder's height limits.

When the orientation changes (`DisplayManager.DisplayListener`) the encoder is recreated, `configId`
increases, a new CONFIG arrives, and the receiver recreates its decoder and answers READY. Packets
with an old `configId` are dropped.

Encoder parameters:

| Parameter | Value |
|---|---|
| `KEY_BITRATE_MODE` | VBR |
| `KEY_BIT_RATE` | H.264: 4, 8 or 16 Mbit/s by the quality setting. H.265: 0.65 of that |
| `KEY_FRAME_RATE` | 30 or 60 by the setting |
| `KEY_MAX_FPS_TO_ENCODER` | the same number, so a 120 Hz screen does not hand the encoder extra frames (API 29+) |
| `KEY_I_FRAME_INTERVAL` | 2 seconds |
| `KEY_REPEAT_PREVIOUS_FRAME_AFTER` | 100 ms, so a still photo does not leave the stream empty |
| `KEY_PRIORITY` | 0 (real time) |

The codec comes from the setting if both sides support it and the encoder could be configured.
Otherwise H.264. H.265 must be hardware: `isHardwareAccelerated` on API 29+, by excluding software
names on API 28. H.264 may be any, otherwise the emulator would not work.

The sender holds a `WifiLock` in `WIFI_MODE_FULL_LOW_LATENCY` (`HIGH_PERF` below API 29), otherwise
Wi-Fi power saving causes latency spikes. The foreground service notification has a Stop button.
`onStop` of `MediaProjection` ends the session.

## Reception (receiver)

- A full-screen `SurfaceView` sized to the stream's proportions, `keepScreenOn`, `WifiLock`
- The decoder gets the CSD as a buffer with `BUFFER_FLAG_CODEC_CONFIG`, so the code is the same for
  both codecs
- On API 30+ `KEY_LOW_LATENCY` is turned on if the decoder supports it
- AUTH_OK reports the TV box's real codecs from `MediaCodecList`

## Hooks for sound

Sound is added as a separate stage, but the protocol format already leaves room for it, so the
video will not have to change and the protocol version will not grow:

- **Negotiation.** Bit 4 of AUTH_OK's codec mask means Opus (`CODEC_AUDIO_OPUS`). The sender sends
  sound only to a receiver that set this bit, so an old receiver will not see it.
- **Setup.** Control message type 0x0A is reserved for AUDIO_CONFIG: codec, sample rate, channels,
  CSD.
- **Data.** Datagram type 0x12 (`PACKET_AUDIO`), the same port 47702. The receiver tells datagrams
  apart by the first byte, so no second socket is needed.
- **Sync.** `ptsMs` of video and sound count from the same session clock, and `PlayoutClock` is
  written to be shared by both streams, not for video alone.
- **Capture.** `AudioPlaybackCapture` works from the same `MediaProjection` object, so there is no
  second permission request. It needs Android 10+ on the sender (the A36 qualifies), the
  `RECORD_AUDIO` permission and foreground service type `mediaProjection|microphone`. A TV box on
  Android 9 is not affected: it only plays.
- **Limitation.** Apps can forbid capturing their sound, and it then stays on the phone.

## Settings

| Setting | Where | Values |
|---|---|---|
| Role | both | receiver, sender |
| Codec | sender | H.264 (default), H.265 |
| Frames per second | sender | 30 (default), 60 |
| Quality | sender | low, medium (default), high |
| Device name | both | `Build.MODEL` by default |
| Remembered devices | both | list with removal |
| Language, theme | both | as in the other apps |

## Code structure

```
xx.zerkalo
  Common.kt            all protocol constants and timings, free of Android
  net/                 Protocol, Pairing, Discovery, ControlChannel
  video/               Fragmenter, FrameAssembler, RetransmitBuffer, PlayoutClock
  sender/              SenderService, ScreenEncoder
  receiver/            ReceiverSession, FrameDecoder
  settings/            AppSettings, Locales
  ui/                  Compose screens
```

`net` and the `video` logic do not depend on Android and are covered by JVM tests: parsing messages
against a reference byte pattern, pairing, assembling frames with losses and reordering, choosing
holes for NACK, a change of `configId`.

## Stages

1. Skeleton: Gradle, manifest, version scripts, the role setting, updater and about, icons.
2. Protocol and pairing with JVM tests.
3. Receiver discovery, control channel, PIN screen.
4. H.264 video with no loss handling: a picture on the TV.
5. Resilience: NACK, key frame requests, clocked playout, screen rotation.
6. H.265, quality and frame rate settings.
7. Later: sound.
8. Later: keep the sender's screen from going off by timeout during mirroring, while letting it dim (`SCREEN_DIM_WAKE_LOCK`).

## To check on hardware

- How many frames the Amlogic decoder holds inside on Android 9, where there is no
  `KEY_LOW_LATENCY`. This adds directly to the latency.
- Whether the A36 has an H.265 encoder and what quality it gives at a low bit rate.
- What minimum sizes and frame alignment the codec and decoder accept at 498×1080.
- Whether `createConfigForDefaultDisplay` works in One UI, or Samsung still offers to pick an app.
