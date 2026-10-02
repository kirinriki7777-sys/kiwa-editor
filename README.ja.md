# Kiwa

日本語の変換と物理キーボードが壊れない、Android タブレット向けのコードエディタ。

[English README](README.md)

Android のコードエディタの多くは、IME が絡むと細かく壊れます。変換中の文字に色が付かない、Ctrl+Z が変換の途中を 1 コマずつ戻す、Tab が未確定の文字に字下げを混ぜる、物理キーボードをつなぐと変換そのものが効かなくなる、といった壊れ方です。Kiwa はそこを直すために作りました。また、手を入れていないファイルは保存してもバイトが 1 つも変わりません（文字コード・BOM・改行コードをそのまま保ちます）。

> **画面は日本語だけです。** メニュー・設定・メッセージは日本語で、翻訳の予定はまだありません。

## 現状

初期の版（0.1.0）です。Redmi Pad 2 Pro の 1 台（Gboard と物理キーボード）で作り、確かめています。Android 14 以上が要ります。

## できること

- タブ、ファイルツリー、メニューバー
- コマンドパレット: 名前でファイルを開く、`>` でコマンド、`:` で行へ移動
- 検索と置換（正規表現も可）、フォルダ全体の検索
- 18 言語の構文の色分け（TextMate の文法）。タブごとに言語を選べる
- UTF-8・Shift_JIS（windows-31j）・EUC-JP・ISO-2022-JP・UTF-16・ISO-8859-1 で開いて保存。BOM と改行コードを保つ
- 戻す・進む、カーソル位置の行き来、行コメント、字下げ、行の移動
- タブレットのキーボードで打ちにくい括弧などを並べた記号キーの列
- 8 つの配色（暗い 4 つ・明るい 4 つ）と、端末に合わせる設定
- 内部ストレージと SD カードの、どこにあるファイルでも開ける

## 入れ方

[Releases](../../releases) から APK を落として入れてください。

- release の APK の署名の証明書は、SHA-256 の指紋が
  `aefe6fdc5fae9f7f750887686021f7597f13ad122a4f896cf3651676f60bc6e4`
  です。`apksigner verify --print-certs Kiwa-*.apk` で確かめられます。
- Kiwa は **すべてのファイルへのアクセス**（`MANAGE_EXTERNAL_STORAGE`）を求めます。コードエディタは、ファイルを置いてある場所（SD カードを含む）でそのまま開く必要があるためです。
- Kiwa にはネットワークの権限がありません。何も集めず、何も送りません。[PRIVACY.md](PRIVACY.md) を見てください。

## ビルド

Android SDK と JDK が要ります（OpenJDK 27 でビルドしています）。`local.properties` の `sdk.dir` に SDK の場所を書いてください。

```bash
./bootstrap-gradle.sh :app:assembleDebug   # Gradle 9.6.0 を .gradle-bootstrap/ に落とす
./tools/check.sh                           # 実機の要らない検査を全部と、単体テスト
```

repo に Gradle wrapper の jar はありません。代わりに `bootstrap-gradle.sh` が `services.gradle.org` から Gradle を落とします。

### release のビルド

release には署名の鍵が要ります。鍵は `~/.gradle/gradle.properties` から読み、鍵が無ければ署名の無い release を作らずに止まります。

```properties
kiwa.keystore=/path/to/your.keystore
kiwa.keystorePassword=...
kiwa.keyAlias=...
kiwa.keyPassword=...
```

```bash
./bootstrap-gradle.sh :app:assembleRelease
```

release のビルドは再現できます。同じコミットを同じ道具で 2 回 clean ビルドすると、バイト単位で同じ APK になります。

### 版とタグ

1. `app/build.gradle.kts` の `versionCode` を 1 つ上げ、`versionName` を決める。
2. `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` を足す。
3. そのコミットに `v<versionName>`（例: `v0.1.0`）のタグを付け、署名した APK を GitHub の release に置く。

## 作りの要点

3 つのモジュールと、検査で守る境界 1 枚でできています。文字を扱うエンジンは [Sora Editor](https://github.com/Rosemoe/sora-editor) を改変せずに使い、IME から来るものは全部その境界が引き受けます。詳しくは [docs/design.md](docs/design.md)（英語）にあります。

## AI を使った開発

Kiwa は AI を大きく使って開発しました。コード・コメント・文書のほとんどは、作者の指示のもとで AI が書いたものです。実機での確認は作者がしました。

- Claude（Anthropic）: Opus 5・Opus 5.5・Sonnet 5
- GPT（OpenAI）: Sol・Luna
- Gemini（Google）: Gemini 3.8 Flash。アプリのアイコンは、Gemini で生成した画像を元にしています。

## ライセンス

Kiwa は [Apache License 2.0](LICENSE) で公開しています。[NOTICE](NOTICE) も見てください。

第三者の部品を、それぞれのライセンスのもとで同梱しています（Sora Editor は LGPL-2.1-or-later、TextMate の文法は MIT など）。一覧と本文は [third_party/](third_party/) にあります。アプリの中では 設定 → このアプリについて → ライセンス から読めます。

## 名前

Kiwa は「際（きわ）」、つまり境目のことです。このアプリの芯は、IME とエディタの境目をどこに引くかにあります。
