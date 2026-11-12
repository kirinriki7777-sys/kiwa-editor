# Kiwa design notes

This is the short version of why Kiwa is built the way it is. The source comments go deeper (they are in Japanese).

## Three modules and one boundary

```
:app             UI. Knows nothing about Sora's types.
  ↓ project(":engine-sora")        the app only calls SoraEngine.create()
:engine-sora     Sora implementation of EditorEngine / ImeBoundary.
                 The only module allowed to import io.github.rosemoe.
  ↓ api(project(":editor-adapter"))
:editor-adapter  Abstract editor engine. No dependencies.
```

Kiwa does not write its own text engine. It uses [Sora Editor](https://github.com/Rosemoe/sora-editor) 0.24.6 as published, without a fork.

Sora is still at 0.x and its internals keep changing. If its types leak through the whole app, replacing or upgrading the engine costs as much as the app is large. So there is exactly one boundary, and a script guards it: `tools/check-boundary.sh` fails if `:app` or `:editor-adapter` mentions `io.github.rosemoe`. A boundary that only exists in a diagram does not hold.

## What the boundary takes care of: input methods

Japanese (and Chinese, Korean, ...) input breaks in code editors not because the engine is weak. It breaks because nobody owns what the input method (IME) sends. Kiwa makes the boundary own four things:

| Responsibility | What goes wrong without it | Where |
|---|---|---|
| 1. Draw the decorations the IME attaches to the text being composed | The segment being converted gets no highlight | `ComposingOverlay` (drawn through the same span path as syntax highlighting) |
| 2. One conversion = one undo step | Ctrl+Z replays every intermediate state of the conversion | `ComposingBoundaryConnection` owns the batch edit |
| 3. Silence key bindings while composing | Tab inserts indentation into the unconfirmed text | `KiwaCodeEditor` consumes `EditorKeyEvent` |
| 4. Keep the composing path with a hardware keyboard | The editor reports `TYPE_NULL` and conversion stops working entirely | `setDisableSoftKbdIfHardKbdAvailable(false)` |

The places in Sora's source that each rule depends on are cited as `file:line` in the KDoc of those classes.

Text fields outside the editor (command palette, search) also go through the IME. There Kiwa does not filter on unconfirmed text and does not steal keys while a conversion is in progress. This matters because, with a Japanese IME on, typing `>` may arrive as an unconfirmed full-width character. That is also why the palette has buttons to switch its mode, not only the prefix character.

## Never damage a file

The rule: **saving a file you have not edited changes no bytes.**

- **Check the encoding, don't guess it.** Kiwa decodes with each candidate in turn (UTF-8, windows-31j, Shift_JIS, EUC-JP, ISO-2022-JP, ISO-8859-1). It takes the first one that re-encodes to exactly the original bytes. ISO-8859-1 maps bytes 1:1, so every file opens. BOMs (UTF-8, UTF-16) are detected first and kept. See `file/TextFile.kt`.
- **Leave line endings alone.** Sora keeps the line ending of each line and writes it back. Normalizing to LF would turn every line of a CRLF file into a diff.
- **Refuse what the encoding cannot hold.** If you type a character the file's encoding cannot represent, saving stops with a message instead of writing `?`.
- **Save through a temporary file.** Kiwa writes a new, uniquely named file next to the target and renames it over the target. If writing fails, the original stays intact.

The unit tests in `app/src/test/kotlin/dev/kirin/kiwa/file/` cover these round trips: BOM, Shift_JIS, no final newline, empty files, surrogate pairs, undecodable bytes. `ContentRoundTripTest` in `:engine-sora` covers mixed line endings.

## One entry point for settings, one table for commands

**Settings.** All values live in `EditorSettings`. One function, `applyTo`, pushes them into the engine. There are three moments when settings must be applied: at startup, when a setting changes, and when a file is opened (the language is known only then). With three separate code paths, a new setting is eventually forgotten in one of them, and the bug looks like "it works until I open a file". `tools/check-settings.sh` verifies that every setter of `EditorEngine` is called only from `EditorSettings.kt`.

Settings are stored in `filesDir/settings.json`. Unknown keys are dropped and missing keys get defaults, so the file can be edited by hand.

**Commands.** Every action on screen comes from the table in `command/Commands.kt`. The menu bar, the command palette and the symbol key row all read the same table, so one action cannot have two implementations. `tools/check-commands.sh` checks this, and checks that `tools/tables/commands.md` and the code list the same command ids.

## A word processor on the same engine

Each tab is either a code editor or a word processor. The word processor is not a second editor: it is the same Sora document with the code decorations (line numbers, highlighting, the symbol key row) turned off and its own typeface, text size and line height. What is saved is plain text, and the rules in "Never damage a file" apply unchanged.

- **Saving happens when you leave the screen, never on a timer.** A timer would write half-typed states back to the file.
- **One previous version is kept** in app-private storage before each overwrite. Nothing is created next to the file.
- **On launch, the last file you were writing reopens** at the position where you stopped.

## Vertical writing is a view, not a document

Vertical writing (縦書き) changes only how the text is drawn and how touches and arrow keys map to positions. The document, the IME connection, the undo history and the save path stay Sora's, so everything in "What the boundary takes care of" holds in vertical mode too. The text itself is never rewritten (no half-width to full-width conversion, for example).

- **Geometry is plain Kotlin.** `VerticalLayout.kt` imports neither Android nor Sora. It lays out columns from right to left and answers three questions: which offset is at a point, where the cursor for an offset is, and where an arrow key leads. It is tested on the host JVM.
- **The rules come from the platform, not from tables.** Character orientation is ICU's `Vertical_Orientation` property (UTR #50). Whether the font has a vertical form for a character is found by shaping it with and without the `vert` feature and comparing glyph ids. Columns break only where ICU's line break iterator (`ja@lb=strict`) allows, which keeps punctuation off the start of a column. One- and two-digit numbers are set upright in the column.
- **An edit re-lays out only the paragraphs it touched.** Geometry is kept per paragraph (one Sora line), and each drawn column is cached, so typing in a long text stays light. Above 100,000 characters the tab stays horizontal and says so.

The approach of keeping the input component and attaching drawing and input mapping separately follows [MMTextKit](https://github.com/lzdev42/MMTextKit) (MIT).

## Tracing input (debug builds only)

Debug builds can record what happens at the IME boundary as JSONL. Start a session from the "計測" (trace) command.

```
/sdcard/Android/data/dev.kirin.kiwa/files/Documents/kiwa/session-*.jsonl
```

`tools/analyze-composing-paths.py` reads these logs. It counts which of Sora's four branches each `setComposingText` call took (insert, delete, replace, or no change). `tools/check-trace-format.sh` guards the field names that the script depends on. Renaming one would not raise an error; that log line would just stop being counted.

The hooks live in `:engine-sora`, because the app cannot reach the editor's `InputConnection`. In release builds the trace is null and the hooks are never called.

## Checks

`tools/check.sh` runs everything that can be checked without a device: the boundary, the language table against the bundled grammars, the single settings entry point, the command table, the trace format, the launcher icon, and the unit tests.

Some things can only be checked on a real device with a real IME: the conversion highlight, undo granularity during conversion, Tab while composing, hardware keyboards, and `diff` after saving. Test those by hand before a release.
