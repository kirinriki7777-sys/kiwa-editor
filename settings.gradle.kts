pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Kiwa"

// 3モジュールに割ってあるのは、差し替え可能性を「設計図」でなく「検査」で守るため。
//   :editor-adapter — 編集エンジンの抽象。依存ゼロ。Sora の型を1つも知らない
//   :engine-sora    — その Sora 実装。io.github.rosemoe を知ってよいのはここだけ
//   :app            — UI。:editor-adapter と、生成の口だけを :engine-sora に頼る
// この形なら「:app に Sora の import が 0 件」が grep で機械検査できる（check-boundary.sh）。
include(":app")
include(":editor-adapter")
include(":engine-sora")
