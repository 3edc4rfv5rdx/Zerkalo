# Zerkalo

Mirrors an Android phone's screen to a TV through an Android TV box, over the home Wi-Fi.
No account and no cloud: the two devices talk to each other directly. The only internet
use is the update check, which can be turned off in the settings.

Made for phones that cannot output video over USB, such as the Samsung Galaxy A series.

**Status: early version.** Mirroring has been tried between an emulator and a phone. It
has not been tried on a real TV box yet.

## Install

Download `zerkalo-<version>-universal.apk` from the
[latest release](https://github.com/3edc4rfv5rdx/Zerkalo/releases/latest) and install it on
both devices. The same APK serves the phone and the TV box.

## How it works

One app with two roles, switched in the settings. A TV box starts as the receiver and
anything else as the sender.

1. Open Zerkalo on the TV box. It shows its name and a four-digit PIN.
2. Open Zerkalo on the phone and pick the box from the list, or enter its IP address.
3. Enter the PIN once. After that the phone connects with one tap.
4. Allow screen capture. Android asks every time a mirroring session starts.
5. Minimize Zerkalo and open anything you like: the whole screen is mirrored, so it works
   with any player.

Stop from the app or from the notification. On the TV box, Back ends the session.

## Features

- H.264 by default, H.265 when both devices have hardware for it
- 30 or 60 frames per second and three quality levels, set on the sender
- Follows the phone's rotation and keeps the picture's proportions on the TV
- Repeats lost Wi-Fi packets, and holds the last good picture when a frame cannot be saved
- Remembers paired receivers and their addresses
- The receiver is operated with a TV remote
- English, Russian and Ukrainian

## Limitations

- Video only for now. Sound is planned.
- The stream is not encrypted. The PIN keeps other devices from connecting, but anyone on
  the same network can capture what is shown.
- DRM video and screens that forbid capture appear black. Android blanks them.
- Both devices must be on a network that lets its clients reach each other. A guest network
  with client isolation will not work.
- The receiver is started by hand from the TV box's launcher, and works only while its
  screen is open.

## Requirements

- Android 9 or newer on both devices
- An H.264 encoder on the sender and a decoder on the receiver
- Wi-Fi 5 GHz is recommended

## Building

Kotlin and Jetpack Compose, one `:app` module, no native code. The updater and the About
dialog are compiled in from the [shared](https://github.com/3edc4rfv5rdx/shared) repository,
which has to be cloned next to this one as `../shared`.

```
./00-MakeAll.sh
```

The numbered scripts in the project root are the individual build and release steps, and
`AGENTS.md` describes how they fit together. The APK is written to
`OUT/zerkalo-<version>-universal.apk`. Release signing reads `~/.my-safe/key.properties`.

The design and the network protocol are specified in [SPEC.md](SPEC.md).

## License

GPL-3.0, see [LICENSE](LICENSE).
