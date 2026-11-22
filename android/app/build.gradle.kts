plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val generatedBridgeAssetsDir = layout.buildDirectory.dir("generated/bridgeAssets")

val syncBridgeAssets by tasks.registering(Copy::class) {
    from(rootProject.file("../bridge")) {
        include("core.py", "server.py")
    }
    into(generatedBridgeAssetsDir.map { it.dir("bridge") })
}

android {
    namespace = "com.tabletagent.local"
    compileSdk = 35

    sourceSets {
        getByName("main") {
            assets.srcDir(generatedBridgeAssetsDir)
        }
    }

    defaultConfig {
        applicationId = "com.tabletagent.local"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.apache.commons:commons-compress:1.26.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

tasks.named("preBuild") {
    dependsOn(syncBridgeAssets)
}
