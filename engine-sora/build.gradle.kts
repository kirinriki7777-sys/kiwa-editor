plugins {
    id("com.android.library")
}

// このモジュールだけが Sora を知ってよい。:app からは SoraEngine.create() 以外見えない。
android {
    namespace = "dev.kirin.kiwa.engine.sora"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

}

dependencies {
    // 抽象は api で出す。:app はこちらの型だけを見る。
    api(project(":editor-adapter"))
    implementation(platform("io.github.rosemoe:editor-bom:0.24.6"))
    implementation("io.github.rosemoe:editor")
    // 構文ハイライト（E5）。文法とテーマは assets/textmate/ に同梱してある。
    // oniguruma-native は入れない ── 無ければ NativeOnigConfig.isAvailable() が false を返して
    // joni（純 Java）へ落ちる作りなので、native を足す判断は実機で遅いと分かってからでいい。
    implementation("io.github.rosemoe:language-textmate")

    testImplementation("junit:junit:4.13.2")
}
