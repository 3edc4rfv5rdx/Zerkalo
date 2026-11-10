# Zerkalo

Mirrors an Android phone's screen to a TV through an Android TV box, over the home Wi-Fi.
No account, no cloud and no internet: the two devices talk to each other directly.

Made for phones that cannot output video over USB, such as the Samsung Galaxy A series.

**Status: in development.** The app skeleton and the role setting are in place. Mirroring
itself is not implemented yet. See the stages in [SPEC.ru.md](SPEC.ru.md).

## How it will work

One app with two roles, switched in the settings. A TV box starts as the receiver and
anything else as the sender.

1. Open Zerkalo on the TV box. It shows its name and a four-digit PIN.
2. Open Zerkalo on the phone and pick the box from the list, or enter its IP address.
3. Enter the PIN once. After that the phone connects with one tap.
4. Allow screen capture. Android asks every time a mirroring session starts.

## Planned features

- H.264 by default, H.265 when both devices support it
- 30 or 60 frames per second and three quality levels
- Follows the phone's rotation and keeps the picture's proportions on the TV
- Recovers from lost Wi-Fi packets without visible corruption
- The receiver is operated with a TV remote
- English, Russian and Ukrainian

## Limitations

- Video only for now. Sound is planned as a separate stage.
- The stream is not encrypted. The PIN keeps other devices from connecting, but anyone on
  the same network can capture what is shown.
- DRM video and screens that forbid capture appear black. Android blanks them.
- Both devices must be on a network that lets its clients reach each other. A guest network
  with client isolation will not work.
- The receiver is started by hand from the TV box's launcher.

## Requirements

- Android 9 or newer on both devices
- Hardware H.264 encoder on the sender and decoder on the receiver
- Wi-Fi 5 GHz is recommended

## Building

Kotlin and Jetpack Compose, one `:app` module, no native code, so a single universal APK
serves the phone and the TV box. The shared updater and About dialog are compiled in from
`../shared`.

```
./00-MakeAll.sh
```

The numbered scripts in the project root are the individual build and release steps, and
`AGENTS.md` describes how they fit together. The APK is written to
`OUT/zerkalo-<version>-universal.apk`. Release signing reads `~/.my-safe/key.properties`.

The design and the network protocol are specified in [SPEC.ru.md](SPEC.ru.md).
