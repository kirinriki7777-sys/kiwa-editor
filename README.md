# Kiwa

A code editor for Android tablets where Japanese input and hardware keyboards just work.

[日本語の README](README.ja.md)

Most Android code editors break in small ways once an input method (IME) is involved. The text being converted is not highlighted, Ctrl+Z replays every step of a conversion, Tab indents half-typed text, or a hardware keyboard disables conversion completely. Kiwa is built around fixing exactly that. It also never changes the bytes of a file you did not edit: encoding, BOM and line endings stay as they were.

> **The user interface is in Japanese only.** Menus, settings and messages are Japanese. Translations are not planned yet.

## Status

Early (0.1.0). Developed and tested on one device, a Redmi Pad 2 Pro with Gboard and a hardware keyboard. Requires Android 14 or newer.

## Features

- Tabs, a file tree, and a menu bar
- Command palette: files by name, commands with `>`, go to line with `:`
- Find and replace (regular expressions supported), and search across a folder
- Syntax highlighting for 18 languages (TextMate grammars), with a per-tab language choice
- Opens and saves UTF-8, Shift_JIS (windows-31j), EUC-JP, ISO-2022-JP, UTF-16 and ISO-8859-1, keeping BOMs and line endings
- Undo/redo, back/forward through cursor positions, line comments, indent/outdent, move lines
- A symbol key row for brackets and other characters that are awkward on a tablet keyboard
- Eight colour themes (four dark, four light), plus one that follows the system
- Opens files anywhere on internal storage and SD cards

## Install

Download the APK from [Releases](../../releases) and install it.

- The release APK is signed with a certificate whose SHA-256 fingerprint is
  `aefe6fdc5fae9f7f750887686021f7597f13ad122a4f896cf3651676f60bc6e4`.
  Check it with `apksigner verify --print-certs Kiwa-*.apk`.
- Kiwa asks for **All files access** (`MANAGE_EXTERNAL_STORAGE`). A code editor needs to open files where they are, including on SD cards.
- Kiwa has no network permission. It collects and sends nothing. See [PRIVACY.md](PRIVACY.md).

## Build

You need the Android SDK and a JDK (built with OpenJDK 27). Point `sdk.dir` in `local.properties` at your SDK.

```bash
./bootstrap-gradle.sh :app:assembleDebug   # downloads Gradle 9.6.0 into .gradle-bootstrap/
./tools/check.sh                           # every check that does not need a device, plus the unit tests
```

The repository has no Gradle wrapper jar. `bootstrap-gradle.sh` downloads the Gradle distribution from `services.gradle.org` instead.

### Release builds

A release build needs a signing key. Kiwa reads it from `~/.gradle/gradle.properties` and refuses to build an unsigned release:

```properties
kiwa.keystore=/path/to/your.keystore
kiwa.keystorePassword=...
kiwa.keyAlias=...
kiwa.keyPassword=...
```

```bash
./bootstrap-gradle.sh :app:assembleRelease
```

Release builds are reproducible: two clean builds of the same commit on the same toolchain produce byte-identical APKs.

### Versions and tags

1. Raise `versionCode` by one and set `versionName` in `app/build.gradle.kts`.
2. Add `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`.
3. Tag the commit `v<versionName>` (for example `v0.1.0`) and attach the signed APK to the GitHub release.

## How it is built

Three modules with one guarded boundary. Kiwa uses [Sora Editor](https://github.com/Rosemoe/sora-editor) as its text engine, unmodified. The boundary owns everything the IME sends. See [docs/design.md](docs/design.md).

## How this was made: AI-assisted development

Kiwa was developed with heavy use of AI models. Most of the code, comments and documentation were written by AI under the maintainer's direction. The maintainer tested it on a real device.

- Claude (Anthropic): Opus 5, Opus 5.5, Sonnet 5
- GPT (OpenAI): Sol, Luna
- Gemini (Google): Gemini 3.8 Flash. The launcher icon is based on an image generated with Gemini.

## License

Kiwa is licensed under the [Apache License 2.0](LICENSE). See also [NOTICE](NOTICE).

It bundles third-party components under their own licenses, including Sora Editor (LGPL-2.1-or-later) and TextMate grammars (MIT and others). The full list and license texts are in [third_party/](third_party/), and inside the app under 設定 → このアプリについて → ライセンス.

## The name

Kiwa (際) means *edge* or *boundary* in Japanese. The core of this app is where the boundary between the input method and the editor is drawn.
