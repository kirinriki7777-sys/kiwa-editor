import javax.inject.Inject

plugins {
    id("com.android.application")
}

// release の署名 ── 鍵も合言葉も repo に入れない。~/.gradle/gradle.properties から受け取る。
//   kiwa.keystore / kiwa.keystorePassword / kiwa.keyAlias / kiwa.keyPassword
// この4つは端末に入れる APK の身元そのもので、**失うと入っているアプリへ
// 上書きインストールできなくなる**（Android は署名が違う APK を別物として断る）。
val signingProps = listOf(
    "kiwa.keystore", "kiwa.keystorePassword", "kiwa.keyAlias", "kiwa.keyPassword",
).associateWith { (project.findProperty(it) as String?)?.takeIf { v -> v.isNotBlank() } }
val keystoreMissing = signingProps.filterValues { it == null }.keys
val keystoreFile = signingProps["kiwa.keystore"]?.let { file(it) }
val canSign = keystoreMissing.isEmpty() && keystoreFile?.isFile == true

// 設定が無いまま release を作らせない。
// 既定の Android Gradle Plugin は**署名の無い APK を黙って出す**ので、
// 端末に入らないものが「ビルドが通った」の顔をする。ここで先に止める。
gradle.taskGraph.whenReady {
    val packaging = allTasks.any { it.name == "packageRelease" || it.name == "bundleRelease" }
    if (packaging && !canSign) {
        val why = when {
            keystoreMissing.isNotEmpty() -> "~/.gradle/gradle.properties に無い: " +
                keystoreMissing.joinToString(" / ")
            else -> "鍵のファイルが無い: $keystoreFile"
        }
        throw GradleException("release の署名ができない（$why）")
    }
}

// ライセンスの文書（LICENSE・NOTICE・third_party/）を assets に入れる。
// repo の中に本文を2つ置かないため、コピーは build の下に作る。設定画面が読む。
abstract class LegalAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val documents: ConfigurableFileCollection

    /** 文書の置き場（repo の根）。中身の変化は [documents] で追う。 */
    @get:Internal
    abstract val root: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun copy() {
        val root = root.get().asFile
        fileSystem.sync {
            from(root.resolve("LICENSE"))
            from(root.resolve("NOTICE"))
            from(root.resolve("third_party")) { into("third_party") }
            into(outputDir)
        }
    }
}

val legalAssets = tasks.register<LegalAssets>("copyLegalAssets") {
    documents.from(
        rootProject.file("LICENSE"),
        rootProject.file("NOTICE"),
        rootProject.fileTree("third_party"),
    )
    root.set(rootProject.layout.projectDirectory)
    outputDir.set(layout.buildDirectory.dir("generated/legal-assets"))
}

android {
    namespace = "dev.kirin.kiwa"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.kirin.kiwa"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        // 計測層を debug ビルドでだけ動かすために要る。
        buildConfig = true
    }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = keystoreFile
                storePassword = signingProps["kiwa.keystorePassword"]
                keyAlias = signingProps["kiwa.keyAlias"]
                keyPassword = signingProps["kiwa.keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (canSign) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 依存の一覧を APK の署名ブロックへ暗号化して埋める AGP の既定を切る。
    // 埋めると、同じコミットを2回ビルドしても APK が一致しない（再現できるビルドにならない）。
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(legalAssets, LegalAssets::outputDir)
    }
}

dependencies {
    // エンジンは生成の口越しにしか触らない。Sora への依存はここに書かない
    //   ── 書けてしまうと tools/check-boundary.sh の意味が無くなる。
    implementation(project(":engine-sora"))

    testImplementation("junit:junit:4.13.2")
    // 設定は JSON 1ファイル。org.json は Android に入っているが、
    // 単体テスト側では android.jar のスタブが既定値を返すだけ（unitTests.isReturnDefaultValues）。
    // **同じ API の実装をテスト側にだけ足す**ことで、保存と読み込みの往復を JVM で測れる。
    testImplementation("org.json:json:20231013")
}
