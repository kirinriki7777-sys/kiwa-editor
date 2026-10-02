# Third-party notices

Kiwa itself is licensed under the Apache License 2.0 (`LICENSE`). It includes, or
is built from, the third-party works listed below. Each is under its own license.
The license texts are in `third_party/licenses/`; the Apache-2.0 text is `LICENSE`.

The same documents can be read in the app: 設定 (Settings) > このアプリについて (About) > ライセンス (Licenses).

| Short name | License text |
|---|---|
| Apache-2.0 | `LICENSE` |
| LGPL-2.1-or-later | `third_party/licenses/LGPL-2.1-or-later.txt` |
| EPL-2.0 | `third_party/licenses/EPL-2.0.txt` |
| MIT | `third_party/licenses/MIT.txt` (the copyright notice of each MIT component is in its row below) |
| TextMate bundle license | `third_party/licenses/TextMate-bundles.txt` |

## 1. Libraries in the APK

Versions are the ones that end up in the APK: Sora Editor is declared in
`engine-sora/build.gradle.kts`; the rest are what Gradle resolves for
`:app:debugRuntimeClasspath` (transitive dependencies of Sora Editor and of Kotlin).
No bundled jar or aar contains a `META-INF/NOTICE` file.

### Sora Editor (LGPL-2.1-or-later)

| Name | Version | License | Source | Copyright |
|---|---|---|---|---|
| Sora Editor `editor` | 0.24.6 | LGPL-2.1-or-later | https://github.com/Rosemoe/sora-editor/tree/0.24.6 (`editor/`) | Copyright (C) 2020-2026 Rosemoe |
| Sora Editor `language-textmate` | 0.24.6 | LGPL-2.1-or-later, with parts under EPL-2.0 (see below) | https://github.com/Rosemoe/sora-editor/tree/0.24.6 (`language-textmate/`) | Copyright (C) 2020-2026 Rosemoe |

- **Not modified.** Kiwa uses the unmodified artifacts `io.github.rosemoe:editor:0.24.6` and
  `io.github.rosemoe:language-textmate:0.24.6` from Maven Central. Their source is the tag
  `0.24.6` of the repository above.
- **"or later".** The POMs say only "LGPL v2.1". The `LICENSE` of the repository is the
  LGPL 2.1 text, and the header of the source files (for example `CodeEditor.java`) says "either
  version 2.1 of the License, or (at your option) any later version". So Kiwa takes it as
  LGPL-2.1-or-later.
- **Replacing the library.** The complete source of Kiwa is published, and the build pulls Sora
  Editor as an ordinary Maven dependency (`engine-sora/build.gradle.kts`). You can change the
  version or point it to your own build of Sora Editor and rebuild the app.

Files inside the Sora Editor artifacts that are **not** under the LGPL:

| Name | Version | License | Source | Copyright |
|---|---|---|---|---|
| Eclipse tm4e (`org.eclipse.tm4e.*` classes inside `language-textmate`, 120 files) | the copy vendored in Sora Editor 0.24.6 | EPL-2.0 | Source: https://github.com/Rosemoe/sora-editor/tree/0.24.6/language-textmate/src/main/java/org/eclipse/tm4e . Upstream: https://github.com/eclipse-tm4e/tm4e | Copyright (c) 2015-2022 Angelo ZERR (and others); Copyright (c) 2022-2024 Sebastian Thomschke and others; Copyright (c) 2023-2024 Vegard IT GmbH and others; Copyright (c) 2018 Red Hat Inc. and others (as written in the file headers) |
| `AndroidEmoji.java` (inside `editor`, one file) | the copy in Sora Editor 0.24.6 | Apache-2.0 | https://github.com/Rosemoe/sora-editor/blob/0.24.6/editor/src/main/java/io/github/rosemoe/sora/text/AndroidEmoji.java | Copyright (C) 2006 The Android Open Source Project |

### Libraries pulled in by Sora Editor `language-textmate`

| Name | Version | License | Source | Copyright |
|---|---|---|---|---|
| gson (`com.google.code.gson:gson`) | 2.13.2 | Apache-2.0 | https://github.com/google/gson | The upstream LICENSE names no copyright holder |
| error_prone_annotations (`com.google.errorprone`) | 2.41.0 | Apache-2.0 | https://github.com/google/error-prone | The upstream LICENSE names no copyright holder |
| snakeyaml-engine (`org.snakeyaml`) | 3.0.1 | Apache-2.0 | https://bitbucket.org/snakeyaml/snakeyaml-engine (tag `snakeyaml-engine-3.0.1`) | The upstream LICENSE names no copyright holder |
| joni (`org.jruby.joni`) | 2.2.7 | MIT | https://github.com/jruby/joni | Copyright (c) 2017 JRuby Team |
| jcodings (`org.jruby.jcodings`) | 1.0.64 | MIT | https://github.com/jruby/jcodings | Copyright (c) 2025 JRuby Team |
| org.eclipse.jdt.annotation (`org.eclipse.jdt`) | 2.4.100 | EPL-2.0 | Source: https://github.com/eclipse-jdt/eclipse.jdt.core/tree/5a85d0d59fafe749d89761cd9035f709e36be17f/org.eclipse.jdt.annotation (the same sources are inside the jar, under `src/`) | Copyright the Eclipse Foundation and others (the jar's `about.html` states the content is provided under EPL-2.0) |

The copyright lines of joni and jcodings are copied from the `LICENSE` files of their
repositories (checked 2026-10-02). `joni` 2.2.7 and `jcodings` 1.0.64 are the versions in the APK.

### AndroidX, Kotlin and JetBrains annotations

| Name | Version | License | Source | Copyright |
|---|---|---|---|---|
| AndroidX `androidx.annotation:annotation` | 1.10.0 | Apache-2.0 | https://cs.android.com/androidx/platform/frameworks/support | The Android Open Source Project (the jar carries its Apache-2.0 text as `META-INF/androidx/annotation/annotation/LICENSE.txt`) |
| AndroidX `androidx.collection:collection` | 1.5.0 | Apache-2.0 | https://cs.android.com/androidx/platform/frameworks/support | The Android Open Source Project (the jar carries its Apache-2.0 text as `META-INF/androidx/collection/collection/LICENSE.txt`) |
| Kotlin standard library (`org.jetbrains.kotlin:kotlin-stdlib`) | 2.3.10 | Apache-2.0 | https://github.com/JetBrains/kotlin | Not checked (the POM states Apache-2.0) |
| JetBrains annotations (`org.jetbrains:annotations`) | 13.0 | Apache-2.0 | https://github.com/JetBrains/intellij-community | Not checked (the POM states Apache-2.0) |

## 2. TextMate grammars and language configurations

Kiwa bundles these files under `engine-sora/src/main/assets/textmate/`. Each grammar file says in
its `information_for_contributors` where it was converted from; the commit given is the one
named in its `version` field. The license and the copyright line are those of that repository.

| Language | File | Original repository and commit | License | Copyright |
|---|---|---|---|---|
| C | `c/syntaxes/c.tmLanguage.json` | https://github.com/jeff-hykin/better-c-syntax @ 34712a6106a4ffb0a04d2fa836fd28ff6c5849a4 | MIT | Copyright (c) 2019 Jeff Hykin |
| C++ | `cpp/syntaxes/cpp.tmLanguage.json` | https://github.com/jeff-hykin/better-cpp-syntax @ 071dd6ecc9eda347bd84c8aa0e0b557396cb6a40 | MIT | Copyright (c) 2019 Jeff Hykin |
| Shell script | `shellscript/syntaxes/shellscript.tmLanguage.json` | https://github.com/jeff-hykin/better-shell-syntax @ 35020b0bd79a90d3b262b4c13a8bb0b33adc1f45 | MIT | Copyright (c) 2019 Jeff Hykin |
| CSS | `css/syntaxes/css.tmLanguage.json` | https://github.com/microsoft/vscode-css @ de9e6beee756760f31b15efbd782735fc25de3db | MIT | Copyright (c) Microsoft Corporation. |
| Go | `go/syntaxes/go.tmLanguage.json` | https://github.com/worlpaker/go-syntax @ c74e22eb9ef32958e3edd130ea750ce78d8b8241 | MIT | Copyright (c) 2023 Furkan Ozalp |
| Java | `java/syntaxes/java.tmLanguage.json` | https://github.com/atom/language-java @ 29f977dc42a7e2568b39bb6fb34c4ef108eb59b3 | MIT | Copyright (c) 2014 GitHub Inc. |
| XML | `xml/syntaxes/xml.tmLanguage.json` | https://github.com/atom/language-xml @ 7bc75dfe779ad5b35d9bf4013d9181864358cb49 | MIT | Copyright (c) 2014 GitHub Inc. |
| JavaScript (with React support) | `javascript/syntaxes/JavaScript.tmLanguage.json` | https://github.com/microsoft/TypeScript-TmLanguage (TypeScriptReact.tmLanguage) @ 4d30ff834ec324f56291addd197aa1e423cedfdd | MIT | Copyright (c) Microsoft Corporation |
| TypeScript | `typescript/syntaxes/typescript.tmLanguage.json` | https://github.com/microsoft/TypeScript-TmLanguage (TypeScript.tmLanguage) @ 48f608692aa6d6ad7bd65b478187906c798234a8 | MIT | Copyright (c) Microsoft Corporation |
| JSON | `json/syntaxes/json.tmLanguage.json` | https://github.com/microsoft/vscode-JSON.tmLanguage @ 9bd83f1c252b375e957203f21793316203f61f70 | MIT | Copyright (c) Microsoft Corporation |
| Lua | `lua/syntaxes/lua.tmLanguage.json` | https://github.com/sumneko/lua.tmbundle (now https://github.com/LuaLS/lua.tmbundle) @ bc74f9230c3f07c0ecc1bc1727ad98d9e70aff5b | MIT | Copyright (c) 2022 最萌小汐 |
| Markdown | `markdown/syntaxes/markdown.tmLanguage.json` | https://github.com/microsoft/vscode-markdown-tm-grammar @ 09c3e715102d08bba4c4ea828634474fc58b6f57 | MIT | Copyright (c) Microsoft 2018 |
| Python (MagicPython) | `python/syntaxes/python.tmLanguage.json` | https://github.com/MagicStack/MagicPython @ b2b4f4ae7b4e6284e80bda8080106b93bd588f9e | MIT | Copyright (c) 2015-present MagicStack Inc. http://magic.io |
| Rust | `rust/syntaxes/rust.tmLanguage.json` | https://github.com/dustypomerleau/rust-syntax @ ca34cf382a7b250144f2ccb99ff9af96912c79f2 | MIT | Copyright (c) 2020 Dustin Pomerleau |
| YAML | `yaml/syntaxes/yaml.tmLanguage.json` | https://github.com/textmate/yaml.tmbundle (Syntaxes/YAML.tmLanguage) @ e54ceae3b719506dba7e481a77cea4a8b576ae46 | MIT (the accompanying `Syntaxes/YAML-license.txt`) | Copyright (c) 2015 FichteFoll |
| Groovy | `groovy/syntaxes/groovy.tmLanguage.json` | https://github.com/textmate/groovy.tmbundle (Syntaxes/Groovy.tmLanguage) @ 85d8f7c97ae473ccb9473f6c8d27e4ec957f4be1 | TextMate bundle license | none named by the repository |
| HTML | `html/syntaxes/html.tmLanguage.json` | https://github.com/textmate/html.tmbundle (Syntaxes/HTML.plist) @ 0c3d5ee54de3a993f747f54186b73a4d2d3c44a2 | TextMate bundle license | none named by the repository |
| Kotlin | `kotlin/syntaxes/Kotlin.tmLanguage` | https://github.com/mathiasfrohlich/vscode-kotlin (`syntaxes/Kotlin.tmLanguage`) | Apache-2.0 | The upstream LICENSE names no copyright holder (`package.json`: license Apache-2.0, publisher mathiasfrohlich) |

Notes:

- Kotlin: the bundled file equals the upstream file at the time of checking (2026-10-02) except for the
  two XML declaration lines at the top, which the bundled file does not have.
- Groovy, HTML: the TextMate bundle license is in `third_party/licenses/TextMate-bundles.txt`.
- The eight grammars Java, XML, HTML, JavaScript, Kotlin, Lua, Markdown and Python are
  byte-identical to the files in the sample app of Sora Editor 0.24.6 (`app/src/main/assets/textmate/`).
  The others (C, C++, CSS, Go, Groovy, JSON, Rust, Shell script, TypeScript, YAML) were taken from the grammars
  shipped with Visual Studio Code, as the `information_for_contributors` of each file states.
  Visual Studio Code is MIT licensed: Copyright (c) 2015 - present Microsoft Corporation
  (https://github.com/microsoft/vscode).

### `language-configuration.json`

| Language | File | Origin | License | Copyright |
|---|---|---|---|---|
| HTML | `html/language-configuration.json` | `extensions/html/language-configuration.json` of https://github.com/microsoft/vscode, through the sample app of Sora Editor 0.24.6 | MIT | Copyright (c) 2015 - present Microsoft Corporation |
| Java | `java/language-configuration.json` | `extensions/java/language-configuration.json` of the same repository, through the same sample app | MIT | same |
| JavaScript | `javascript/language-configuration.json` | `extensions/javascript/javascript-language-configuration.json`, through the same sample app | MIT | same |
| Lua | `lua/language-configuration.json` | `extensions/lua/language-configuration.json`, through the same sample app | MIT | same |
| Markdown | `markdown/language-configuration.json` | `extensions/markdown-basics/language-configuration.json`, through the same sample app | MIT | same |
| Python | `python/language-configuration.json` | `extensions/python/language-configuration.json`, through the same sample app | MIT | same |
| XML | `xml/language-configuration.json` | `extensions/xml/xml.language-configuration.json`, through the same sample app | MIT | same |
| Kotlin | `kotlin/language-configuration.json` | Sora Editor 0.24.6 sample app. It matches `kotlin.configuration.json` of https://github.com/mathiasfrohlich/vscode-kotlin except for the `folding` key | Apache-2.0 | The upstream LICENSE names no copyright holder |

These files are byte-identical to those in the Sora Editor 0.24.6 sample app. Only
`python` is still identical to the current file of Visual Studio Code; the others differ from the
current VS Code files (they are older revisions), so the exact revision they were taken from is not known.
`languages.json` (the table that maps languages to these files) was written for Kiwa.

## 3. Original works of the Kiwa project

| Name | License | Notes |
|---|---|---|
| Color themes `beni`, `kami`, `kinari`, `kiri`, `mori`, `shiro`, `sumi`, `yoru` (`engine-sora/src/main/assets/textmate/<name>.json`) | Apache-2.0, as the rest of Kiwa | Made for Kiwa from its own color table. They are not copies of any bundled theme. |
| App icon (`app/icon-src/`, and the `mipmap-*` images generated from it) | Apache-2.0, as the rest of Kiwa | Made from an image generated with Google Gemini (the original image is `app/icon-src/source-1024.jpg`), then converted to 80x80 pixel art. Google's Terms of Service say that Google does not claim ownership of content generated with its services (https://policies.google.com/terms, read 2026-10-02). |
