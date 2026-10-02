# 設定項目の調査表

| 分類 | 項目 | acode | xed-editor | squircle-ce | Kiwa | 根拠 |
|---|---|---|---|---|---|---|
| 表示 | フォントサイズ | + | + | + | - | acode:src/settings/editorSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/editor/EditorHeaderScreen.kt |
| 表示 | エディタのフォント(書体)選択 | + | + | + | - | acode:src/settings/editorSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/editor/EditorHeaderScreen.kt |
| 表示 | 行番号の表示 | + | + | + | - | acode:src/settings/editorSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/editor/EditorHeaderScreen.kt |
| 表示 | 相対行番号 | + | - | - | - | acode:src/settings/editorSettings.js |
| 表示 | ミニマップ | - | + | - | - | xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt |
| 表示 | 行の高さ/行間隔 | + | + | - | - | acode:src/settings/editorSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt |
| 表示 | カーソルの移動アニメーション | - | + | - | - | xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt |
| 表示 | 折り返し(word wrap) | + | + | + | + | acode:src/settings/editorSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/editor/EditorHeaderScreen.kt |
| 表示 | インデントガイド線 | + | - | - | - | acode:src/settings/editorSettings.js |
| 表示 | 空白文字の可視化 | + | + | + | - | acode:src/settings/editorSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/editor/EditorHeaderScreen.kt |
| 表示 | スティッキースクロール | - | + | + | - | xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/editor/EditorHeaderScreen.kt |
| 表示 | UIズーム(アプリ全体の表示倍率) | + | - | - | - | acode:src/settings/appSettings.js |
| 表示 | フルスクリーン表示 | + | + | + | - | acode:src/settings/appSettings.js xed-editor:core/main/src/main/java/com/rk/settings/app/SettingsAppScreen.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/application/AppHeaderScreen.kt |
| 表示 | 虹色括弧(ネストごとに色分け) | + | - | - | - | acode:src/settings/editorSettings.js |
| 表示 | コード内の色プレビュー(#fffのスウォッチ表示) | + | + | - | - | acode:src/settings/editorSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt |
| 表示 | Lintのガター表示 | + | - | - | - | acode:src/settings/editorSettings.js |
| 配色 | 明暗テーマを複数のプリセットから選ぶ | + | + | + | + | acode:src/pages/themeSetting/themeSetting.js xed-editor:core/main/src/main/java/com/rk/settings/theme/Theme.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/application/AppHeaderScreen.kt |
| 配色 | 端末の明暗設定に自動追従 | + | + | - | + | acode:src/pages/themeSetting/themeSetting.js xed-editor:core/main/src/main/java/com/rk/settings/theme/Theme.kt |
| 配色 | AMOLED(純黒)モード | - | + | - | - | xed-editor:core/main/src/main/java/com/rk/settings/theme/Theme.kt |
| 配色 | Monet/Material You(壁紙連動色) | - | + | - | - | xed-editor:core/main/src/main/java/com/rk/settings/theme/Theme.kt |
| 配色 | アイコンパックの切り替え | - | + | - | - | xed-editor:core/main/src/main/java/com/rk/settings/theme/Theme.kt |
| 配色 | カスタムテーマの自作(色編集画面) | + | - | - | - | acode:src/pages/themeSetting/themeSetting.js |
| 編集 | タブ幅 | + | + | + | - | acode:src/settings/editorSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/codestyle/CodeHeaderScreen.kt |
| 編集 | タブ or スペース | + | + | + | - | acode:src/settings/editorSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/codestyle/CodeHeaderScreen.kt |
| 編集 | 自動インデント | - | - | + | - | squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/codestyle/CodeHeaderScreen.kt |
| 編集 | 自動保存 ON/OFF | + | + | + | - | acode:src/settings/editorSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/editor/EditorHeaderScreen.kt |
| 編集 | 自動保存の遅延時間 | + | + | - | - | acode:src/settings/editorSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt |
| 編集 | 保存時に自動フォーマット | + | + | - | - | acode:src/settings/editorSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt |
| 編集 | 保存時に末尾の空白を削除 | - | + | - | - | xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt |
| 編集 | 保存時に最終行へ改行を挿入 | - | + | - | - | xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt |
| 編集 | コード補完(自動候補表示) | + | + | + | - | acode:src/settings/editorSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/editor/EditorHeaderScreen.kt |
| 編集 | 自動で括弧/クオートを閉じる | - | + | + | - | xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/codestyle/CodeHeaderScreen.kt |
| 編集 | タグの自動閉じ | + | + | - | - | acode:src/settings/editorSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt |
| 編集 | タグの自動リネーム | + | - | - | - | acode:src/settings/editorSettings.js |
| 編集 | Emmet(HTML省略記法展開) | + | - | - | - | acode:src/settings/editorSettings.js |
| 編集 | ピンチズームで文字サイズ変更 | - | - | + | - | squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/editor/EditorHeaderScreen.kt |
| 編集 | 読み取り専用モードで開く(デフォルト) | - | + | + | - | xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/editor/EditorHeaderScreen.kt |
| 言語別 | LSP(言語サーバー)の管理・有効/無効切り替え | + | + | - | - | acode:src/settings/lspSettings.js xed-editor:core/main/src/main/java/com/rk/settings/lsp/LspSettings.kt |
| 言語別 | 外部/カスタムLSPサーバーの追加 | + | + | - | - | acode:src/settings/lspSettings.js xed-editor:core/main/src/main/java/com/rk/settings/lsp/LspSettings.kt |
| 言語別 | フォーマッタの言語ごとの割り当て/管理 | + | + | - | - | acode:src/settings/formatterSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/FormatterSettings.kt |
| 言語別 | 言語ごとの補完辞書(language completion package) | + | - | - | - | acode:src/settings/editorSettings.js |
| 言語別 | TextMate文法によるキーワード補完 | - | + | - | - | xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt |
| キー | 拡張ツールバー(記号キー列)の表示ON/OFF | + | + | + | - | acode:src/settings/appSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/editor/EditorHeaderScreen.kt |
| キー | 拡張ツールバーのキー配列/内容をカスタマイズ | + | + | + | - | acode:src/settings/appSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/editor/EditorHeaderScreen.kt |
| キー | ショートカット(キーバインド)のカスタマイズ・リセット | + | + | + | - | acode:src/settings/appSettings.js xed-editor:core/main/src/main/java/com/rk/settings/keybinds/KeybindingsScreen.kt squircle-ce:feature-shortcuts/impl/src/main/kotlin/com/blacksquircle/ui/feature/shortcuts/ui/shortcuts/ShortcutsScreen.kt |
| キー | 物理キーボード接続時のソフトキーボード表示制御 | - | + | + | - | xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/editor/EditorHeaderScreen.kt |
| キー | 拡張ツールバーのトリガー方式(タップ/クリック) | + | - | - | - | acode:src/settings/appSettings.js |
| キー | 拡張ツールバーの背景色分け/分割表示 | - | + | - | - | xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt |
| ファイル | 隠しファイルの表示 | + | + | + | - | acode:src/settings/filesSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/files/FilesHeaderScreen.kt |
| ファイル | ファイル一覧の並び替え | + | + | + | - | acode:src/settings/filesSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/files/FilesHeaderScreen.kt |
| ファイル | フォルダを一覧の上に固定表示 | - | - | + | - | squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/files/FilesHeaderScreen.kt |
| ファイル | 除外パターン(検索/コピー時に除外するフォルダ) | + | + | - | - | acode:src/settings/appSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt |
| ファイル | 最後に開いたファイル/フォルダを記憶 | + | + | - | - | acode:src/settings/appSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt |
| ファイル | ファイル変更の外部監視 | + | - | - | - | acode:src/settings/appSettings.js |
| ファイル | デフォルトの文字コード(エンコーディング) | + | + | + | - | acode:src/settings/appSettings.js xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/files/FilesHeaderScreen.kt |
| ファイル | エンコーディングの自動判定 | - | - | + | - | squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/files/FilesHeaderScreen.kt |
| ファイル | デフォルトの改行コード | - | + | + | - | xed-editor:core/main/src/main/java/com/rk/settings/editor/SettingsEditorScreen.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/files/FilesHeaderScreen.kt |
| ファイル | 全ファイルへのストレージアクセス権限の導線 | + | + | + | - | acode:src/settings/terminalSettings.js xed-editor:core/main/src/main/java/com/rk/settings/app/SettingsAppScreen.kt squircle-ce:feature-settings/impl/src/main/kotlin/com/blacksquircle/ui/feature/settings/ui/files/FilesHeaderScreen.kt |
