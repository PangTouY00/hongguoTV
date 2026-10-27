plugins { kotlin("jvm"); application }
// 这条分支的产物要跑在 Android 4.4 上：那里的 Java 库没有 java.time、Stream、Map.of 这些 Java 8+ 的东西，
// 所以字节码目标停在 1.8，依赖也换成不要求 Java 8 的版本（OkHttp 3.12 / BouncyCastle jdk15on / jsoup 1.11）。
kotlin { jvmToolchain(17); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) } }
// core 产物与 app 一起跑在 Android 4.4 的旧虚拟机上：字节码必须是 Java 8（class 52），
// 不能跟着构建机的 JDK 17/18 走，否则 4.4 上直接 VerifyError。
java { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
dependencies {
    implementation("com.squareup.okhttp3:okhttp:3.12.0")
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    implementation("org.json:json:20240303")
    implementation("org.jsoup:jsoup:1.11.3")
    testImplementation("junit:junit:4.13.2")
}
application { mainClass.set("com.hongguotv.core.ProbeKt") }
