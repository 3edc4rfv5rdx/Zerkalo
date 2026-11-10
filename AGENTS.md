# The build set — what holds it together

`README.md` says how to use these scripts. This file says what must stay true
while changing them, because most of it is a contract between files rather than
anything visible inside one.

This file is copied into a project along with the scripts, so it is also the
working rules for an agent in that project.

## The shape of it

- `99-project.conf` — every value that differs between projects. Nothing else
  names a project, a device or a path.
- `98-common` — every function more than one step needs. Sourced, never run.
- `NN-*.sh` — one step each. The number says the order, not that all are run.
  Above 77 are the things that are not steps of a build.
- `ADD/gradle-version-wiring.kts` — the Gradle side. Not a build file: three
  pieces to paste into `app/build.gradle.kts`.

A new step goes in its own numbered file, named `NN-PascalCase.sh`. The two
files that are not steps are lowercase and carry no `.sh` — `98-common`,
`99-project.conf` — so the listing says at a glance what is runnable. Anything a second step would need
goes into `98-common` first. No step reads another step's insides. `ADD/` is
git-ignored scratch, except the files this set puts there on purpose.

Sharing the common file is a decision, not an oversight: do not "restore" the
duplication here. Not every project on this machine made the same choice — some
keep each numbered script self-contained on purpose — so check the convention
that project's own copy of this file states before carrying pieces between
them.

## The five contracts

**1. The name.** `PROJECT` in `99-project.conf` and `projectName.set(...)` in the
Gradle wiring must be the same string — it is written there twice, once for the
release rename and once for the debug one. Gradle renames the APKs to
`<PROJECT>-<version>-<abi>.apk`, a debug build carrying `-debug` after
the ABI, and `19-LinkOut.sh`, `22-RelUpload.sh` and `99-CopyToAPKX.sh` all find
files by that shape.
`97-InitProject.sh` sets both; a hand edit to one of them breaks the upload
with "no arm64 APK for this tag" long after the mistake.

**2. Exit code 3 means "nothing to work on".** No emulator, no phone, no icon
master. `00-MakeAll.sh` reads it as a skip and carries on; anything else
non-zero it reports as a failure and, for a fatal step, stops. A new step that
can find nothing to do must exit 3, not 0 and not 1. Also: never end a step on
`sleep` — that reports the sleep's exit code, not the work's. Use `pause` after
the status is captured.

**3. `build_number.txt` is the only version.** Three shell assignments, read by
the scripts with `.` and by Gradle as a properties file — so no quotes, no
spaces around `=`. Gradle reads `build` as `versionCode` and `version` as
`versionName`. Only `version_bump`/`version_save` in `98-common` write it;
no step writes it directly.

**4. The version line moves by itself, once per feature.** `10-MakeRelease.sh`
raises `major.minor` when `## Unreleased` holds a `- N:` entry *and* the newest
tag is still on the line the build is on. That second half is what stops it
firing on every build after the feature — do not "simplify" it away.

**5. The changelog is parsed, not just read.** Four things depend on its shape:
- `## Unreleased` — exact heading. `version_bump` reads entries under it,
  `20-MakeTag.sh` opens the new version's section directly beneath it.
- `- N:` or `- N ` at the start of a line — a feature. Continuation lines must
  be indented, or a wrapped sentence starting with "N" becomes one.
- `## v<version>`, optionally followed by ` (<build date>)` — the section
  `22-RelUpload.sh` turns into release notes, stopping at the previous tag's
  section. `20-MakeTag.sh` writes the date there; every reader matches the tag
  followed by a space or the end of the line, so a section stamped without one
  still counts.
- `> ` — a service line, not an entry: the legend and the note about the
  order. `22-RelUpload.sh` copies the one matching `> N=` into the notes.

## Where to change what

| Change | Touch |
|---|---|
| A new project uses the set | `bash 97-InitProject.sh` — never the files by hand |
| Different ABIs are built | `LINK_ABIS` in the conf **and** `splits`/`RenameReleaseApks` in the wiring |
| A different emulator, branch, remote | the conf only |
| The launcher blue | `ICON_BG_COLOR` in the conf **and** `ic_launcher_background` in `res/values/colors.xml` |
| Something two steps both need | `98-common`, then call it from both |
| A new step | a new numbered file that obeys contract 2 |

## Two things that look like bugs and are not

- **Icons are not generated during a build.** `02-MakeIcons.sh` rewrites tracked
  files; doing that inside a build leaves the tree dirty, which stops the version
  bump being folded into the previous commit and ships an APK carrying resources
  no commit recorded. It is its own step and its own commit. `00-MakeAll.sh`
  deliberately does not call it.
- **The version bump is folded into the previous commit** only when HEAD is on no
  remote branch yet *and* `build_number.txt` is the only modified file. Both
  halves matter: the first keeps it from rewriting pushed history, the second
  from sweeping unrelated work into an amend.

## Working rules in a project that uses this set

**Language.** Everything written into the project is English: code, identifiers,
comments, `CHANGELOG.md`, `README.md`, commit messages, this file. Only the
conversation with the user is in Russian, and only UI-facing strings are
localised — and those through the localisation function, never hardcoded, with
every declared locale filled in, never left as an English placeholder.

**Scope.** Do what was asked and nothing beside it. No drive-by refactors, no
renames nobody wanted, no "while I was in there" fixes, no reformatting of
untouched lines. Something worth doing that was not asked for is proposed in one
sentence and waits for a yes. When the approach itself looks wrong, say so
before writing the code, not after.

**Committing.** Only on an explicit request — "запиши", "коммит", "commit".
A description of the workflow ("по-фично, с коммитом") is not a standing
permission: wait for the word each time. Then:

- One fix, one commit. Nothing unrelated from the working tree goes in with it.
- Every commit carries its `CHANGELOG.md` entry — one short sentence under
  `Unreleased`, newest on top, prefixed with the letter that fits. `N:` only
  when the app can do something it could not before: that letter moves the
  version line at the next release build.
- Exactly the intended changes: no chmod, no side tweaks. A file mode the user
  changed is intentional — commit it as it is.
- Version files are the user's: never edit `build_number.txt` by hand, but stage
  it when the user's build has left it dirty rather than leaving it behind.
- After committing, stop. The user pushes.

**Building.** Do not build or install — not even to check that it compiles. The
user builds and installs. Wanting to see an exit code is not a reason to install
anything. If you are unsure the code compiles, say so.

**Devices.** Run Android and instrumentation tests only on an emulator. Never
install, launch, uninstall or otherwise touch a physical device over `adb`
without explicit permission, and do not run `connected*AndroidTest` while one is
attached. `./05-Lint.sh` and `./06-Test.sh` compile the code like any build, so
they are asked for too — unless the user has said, in this project, that you may
run them yourself. Then they are the only two.

**Releases only.** There is no debug build in this set, on purpose: the user
builds releases and nothing else. Do not add a debug step, do not offer a debug
APK, and do not write debug-only behaviour into the app. The debug *variant* is
still what `05-Lint.sh` and `06-Test.sh` check — that is Gradle's business, not
an APK anybody installs.

## Smaller conventions, learned the hard way

- **Commit messages**: one line, subject only. No explanatory body.
- **The branch is `main`.** On `git init`, rename `master` to `main` at once;
  releases go out from `main` and `21-PushTag.sh` refuses any other branch.
- **A question is a question.** "А если сделать X?" wants prose, not an edit and
  not an options picker. Diagnose out loud before writing a fix; when the change
  is a UI decision, show the variants and wait for a pick.
- **Audit files** (`ADD/tofix*.md`): verify each finding against the code before
  touching anything — real / not real / cannot verify, with evidence — fix only
  the confirmed ones, one commit each, and mark the item done by prefixing its
  heading with `FIXED` and the commit's short hash, never by appending:
  `## FIXED a1b2c3d — Finding 4: …`. The hash is the point — it says which
  change closed the item, so a finding can be read back against its diff months
  later. The hash exists only once the fix is committed, so the mark follows in a
  second, tiny commit of its own — that is the shape of it, not an oversight.
  Items in a plain todo list get a leading `+`.
- **Temporary debug logging** starts with `>>> ` so it can be grepped out again.
- **Say what to check.** A finished change comes with the one or two things the
  user should look at to see it working.
