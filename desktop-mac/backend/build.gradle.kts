plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
}
application { mainClass.set("com.hongguotv.desktop.MainKt") }
