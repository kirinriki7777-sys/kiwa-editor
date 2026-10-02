# Privacy Policy

Kiwa does not collect, store remotely, or share any personal data.

- **No network access.** The app does not request the `INTERNET` permission, so it cannot send anything off your device.
- **No analytics, ads, or crash reporting.** Kiwa contains no tracking or advertising libraries.
- **Your files stay where they are.** Kiwa reads and writes only the files you open or save. It asks for All files access (`MANAGE_EXTERNAL_STORAGE`) only so that it can open files in place, including on SD cards.
- **Settings stay on the device.** Editor settings are saved in the app's private storage (`settings.json`) and removed when you uninstall the app.
- **Trace logs are debug-only.** Debug builds can write input trace logs (JSONL) to the app's own folder on shared storage, but only when you start a trace session. Release builds do not have this feature enabled.

If this policy changes, the change will be published in this file in the project's repository.

---

# プライバシーポリシー

Kiwa は個人のデータを集めず、外へ送らず、誰とも共有しません。

- **ネットワークを使いません。** `INTERNET` の権限を求めないので、端末の外へ何も送れません。
- **解析・広告・クラッシュの報告をしません。** 追跡や広告のライブラリは入っていません。
- **ファイルは置き場所のまま扱います。** 読み書きするのは、開いたファイルと保存したファイルだけです。すべてのファイルへのアクセス（`MANAGE_EXTERNAL_STORAGE`）を求めるのは、SD カードを含めて、ファイルをその場で開くためです。
- **設定は端末の中に残ります。** エディタの設定はアプリ専用の場所（`settings.json`）に保存され、アプリを消すと一緒に消えます。
- **計測のログは debug ビルドだけです。** debug ビルドは、計測を始めた時だけ、入力の記録（JSONL）を共有ストレージのアプリ専用のフォルダに書きます。release ビルドではこの機能は動きません。

この方針を変える時は、repo のこのファイルで公開します。
