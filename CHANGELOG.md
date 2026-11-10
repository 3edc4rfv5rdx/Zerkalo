# CHANGELOG
> Newest entries on top.
> N=new feature, E=error fix, F=fine-tune, R=refactor, I=infrastructure

## Unreleased
- N: A file picked on the sender is served over HTTP and opened in a player on the receiver, with seeking.
## v0.2.13 (2026-11-10)
- I: A send-a-file mode is in the plan.
- N: The receiver can match sound to the picture by itself, can be told not to, and takes a manual offset of up to 80 ms.
- N: The sound apps play goes to the receiver along with the picture.
- N: The sender's screen no longer goes off by timeout while mirroring; it may still dim.
- I: 23-ToUpdate.sh publishes the manifest the in-app updater reads.
## v0.1.9 (2026-11-10)
- I: ADD is tracked by git, and local files such as test videos live in the ignored tmp folder.
- I: The Russian specification stays beside the English one.
- I: The specification is in English, in SPEC.md.
- I: The README describes the working app, and the project carries the GPL-3.0 license.
- F: The exit button sits in the top left corner.
- F: The phone on the icon is wider and carries a play mark too.
- N: An Exit button on both roles, and a full-screen mirroring panel on the sender with a Minimize button.
- N: The sender's settings choose the codec, H.264 or H.265, the frame rate and the quality.
- F: A late frame is waited for longer before the stream restarts from a key frame, which means fewer stutters.
- N: Lost packets are sent again, a broken stream picks up at a new key frame, frames are shown on time, and the picture follows the sender's rotation.
- N: Receivers once connected stay in the sender's list, so their address need not be typed again.
- N: The sender's screen is captured, encoded as H.264 and shown full screen on the receiver.
- N: Receivers are found on the network, paired with a PIN once, and stay connected until either side leaves.
- I: Wire protocol and PIN pairing with JVM tests, with room reserved for sound.
- N: App skeleton with a receiver or sender role in the settings, theme, language, updater and About.
- I: The version is major.minor.build — the date left it — so the tag is v0.1.1 and an artifact <project>-0.1.1-arm64-v8a.apk, each number written once; 20-MakeTag.sh puts the build date after the tag in the CHANGELOG heading, for the reader
- E: The version line looks only at release tags, so a tag like "duplex" can no longer answer which line the last release went out on
- I: Lint reruns instead of reprinting an up-to-date report, and says when the report was written
- I: The previous run's test results are cleared before a run, and a failed test prints its name and the first lines of its message
- I: The clean-tree check refuses an untracked file too, so nothing can go into the APK without going into the tag
- I: The .apkx helper is 99-CopyToAPKX.sh, the number it carries in the older projects
- I: One CHANGELOG legend across every project here — N/E/F/R/I, newest on top, the type letter always followed by a colon
- I: One name for every artifact: `<project>-<version>-<build>-<abi>.apk`, and the tag it goes out under is `v<version>-<build>`.
- I: The build scripts are in place: 97-InitProject.sh names a copy of the template after the folder it is in, and the blue the launcher plate is painted in is named once, in 99-project.conf.
