import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }
val signingProperties = Properties().apply { val config = rootProject.file("keystore.properties"); if (config.exists()) config.inputStream().use { load(it) } }
fun signingValue(env: String, key: String): String? = System.getenv(env) ?: signingProperties.getProperty(key)

android {
    namespace = "com.hongguotv.nativeapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.hongguotv.nativeapp"
        minSdk = 19
        targetSdk = 36
        versionCode = System.getenv("HONGGUOTV_VERSION_CODE")?.toInt() ?: 53
        versionName = System.getenv("HONGGUOTV_VERSION_NAME") ?: "0.1.12"
    }
    buildFeatures { buildConfig = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("robolectric.dependency.repo.url","https://repo.maven.apache.org/maven2") }
    }
    signingConfigs {
        create("delivery") {
            storeFile = signingValue("HONGGUOTV_KEYSTORE", "storeFile")?.let { file(it) }
            storePassword = signingValue("HONGGUOTV_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("HONGGUOTV_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("HONGGUOTV_KEY_PASSWORD", "keyPassword")
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("delivery"); isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    // Android 4.4 的 Java 库没有 Java 8 那套 API，核心库脱糖在这里帮不上忙：
    // 代码本身按 Java 8 目标写，Java 8 API 的用法在 core 侧已经换成 API 19 可用的写法。
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
    packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module") }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1") {
        // API 23 tests use legacy graphics, without the cross-platform native renderer.
        exclude(group="org.robolectric",module="nativeruntime-dist-compat")
    }
    implementation("com.google.zxing:core:3.3.3")
    implementation("com.squareup.okhttp3:okhttp:3.12.0")
    implementation(project(":core")) { exclude(group = "org.json") }
    // 播放走 ExoPlayer（见 nativeapp/ExoPlayerPlayer.kt）。1.4.1 是 androidx.media3 里最后一个
    // minSdk 19 的版本：它自己的 constants.gradle 在 1.4.1 写的是 19，1.5.0 起抬到 21，
    // 所以 Android 4.4 只能用这一版。
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
}
