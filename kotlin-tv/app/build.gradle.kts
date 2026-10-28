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
        // API 19 没有自动 multidex（AGP 只在 minSdk 21 起自动开启），而这条分支的依赖总量已经超过
        // 单个 dex 的 65536 方法上限：显式开启，并由 HongguoApplication 在 attachBaseContext 装载第二个 dex。
        multiDexEnabled = true
        // 强制软解：在硬解可用的开发机上把整条播放路切到随包 FFmpeg，用来验证软解链路本身。
        // 正式包不带这个开关（默认 false），设备上有硬解就仍然走设备自己的解码器。
        buildConfigField("boolean", "FORCE_SOFT_DECODE",
            (System.getenv("HONGGUOTV_FORCE_SOFT_DECODE")?.toBoolean() ?: false).toString())
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
    // 随包的软件解码器（见 nativeapp/SoftwareDecoder.kt 与 IjkPlayerPlayer.kt）。
    // 这台 Android 4.4 电视没有任何 H.265 解码器，而播放接口给它的每一档都是 bytevc1：
    // 系统硬解那条路对它没有出路，能播的唯一办法是自带解码器。
    // 这条线是 ijkplayer 0.8.8（携带 FFmpeg 4.0，内置 hevc 解码器），官方支持到 Android 4.1；
    // 这里的坐标是腾讯 IoT 重新发布到 Maven Central 的那一份（原坐标 tv.danmaku.ijk.media 只上过 jcenter），
    // 包名与 JNI 符号都保持 ijkplayer 原样，minSdk 9。只带 armeabi-v7a：目标电视是 32 位。
    implementation("com.tencent.iot.thirdparty.android:ijkplayer-java:2.0.17")
    implementation("com.tencent.iot.thirdparty.android:ijkplayer-armv7a:2.0.17")
    // minSdk 19 需要显式带 multidex 支持库；minSdk 21 起系统自带，不需要这一行。
    implementation("androidx.multidex:multidex:2.0.1")
}
