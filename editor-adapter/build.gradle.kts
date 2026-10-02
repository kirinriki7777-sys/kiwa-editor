plugins {
    id("com.android.library")
}

// 依存はゼロ。この層は「編集エンジンの抽象」であって、特定のエンジンにも
// サポートライブラリにも依存しない。Sora の型が1つでも漏れると
// 差し替え可能性が失われるので、check-s6.sh がそれを機械で検査する。
android {
    namespace = "dev.kirin.editoradapter"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
