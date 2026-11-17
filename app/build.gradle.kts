import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signeringsnøkkelen ligg UTANFOR repoet. Passord og sti blir lesne frå
// keystore.properties (som er lista i .gitignore). Finst ikkje fila, blir
// release-bygget usignert.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "no.frodolini.podspelar"
    compileSdk = 35

    defaultConfig {
        applicationId = "no.frodolini.podspelar"
        minSdk = 26
        targetSdk = 35
        // Kvar ny versjon MÅ ha høgare versionCode, elles vil ikkje telefonen oppdatere.
        versionCode = 2
        versionName = "0.2"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// APK-fila får namn etter versjonen, t.d. Podspelar-0.1-release.apk
base {
    archivesName.set("Podspelar-${android.defaultConfig.versionName}")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Avspeling i bakgrunnen med kontrollar på låseskjerm og headset
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    // Biletlasting (omslag)
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.json:json:20240303")
}

// Lèt testane lesa ekte feedar frå ei mappe: ./gradlew test -Dfeeds=/sti/til/mappe
tasks.withType<Test>().configureEach {
    System.getProperty("feeds")?.let { systemProperty("feeds", it) }
    testLogging { showStandardStreams = true }
}
