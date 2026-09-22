import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
val localConfig = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
fun publicConfig(name: String) = providers.gradleProperty(name).orNull ?: localConfig.getProperty(name, "")
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "") + "\""
require(publicConfig("SUPABASE_PUBLISHABLE_KEY").let { it.isBlank() || it.startsWith("sb_publishable_") }) { "Only SUPABASE_PUBLISHABLE_KEY may be embedded in the Android client" }
layout.buildDirectory.set(file("build-pilot"))
android {
    namespace = "it.pat.collettori"
    compileSdk = 36
    defaultConfig {
        applicationId = "it.pat.collettori.pilot"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "0.15"
        buildConfigField("String", "SUPABASE_URL", quoted(publicConfig("SUPABASE_URL")))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", quoted(publicConfig("SUPABASE_PUBLISHABLE_KEY")))
        buildConfigField("boolean", "DEMO", "false")
        buildConfigField("boolean", "DEV_ADMIN", "false")
        buildConfigField("boolean", "PHOTO_UPLOAD_SIMULATED", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("demo") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            buildConfigField("boolean", "DEMO", "true")
            buildConfigField("boolean", "DEV_ADMIN", "true")
            matchingFallbacks += listOf("debug")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    testBuildType = providers.gradleProperty("testBuildType").orElse("debug").get()
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].assets.srcDir("../../shared")
    sourceSets["test"].resources.srcDir("../../shared")
    sourceSets["test"].resources.srcDir("src/demo/assets")
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencyLocking { lockAllConfigurations() }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
}
