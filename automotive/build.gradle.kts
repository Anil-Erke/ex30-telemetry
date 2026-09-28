import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Imzalama bilgileri depoya girmeyen keystore.properties dosyasindan okunur.
// >>> FILL IN: COPY keystore.properties.example TO keystore.properties AND ENTER YOUR OWN KEYSTORE.
// >>> DOLDURUN: keystore.properties.example DOSYASINI keystore.properties OLARAK KOPYALAYIP KENDI ANAHTARINIZI GIRIN.
// Dosya yoksa release derlemesi imzasiz uretilir; debug derlemesi her durumda calisir.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// Drive'a aktarim ucunun adresi ve anahtari — bunlar da depoya GIRMEZ.
// Dosya yoksa alanlar bos kalir; uygulama derlenir, ekrandaki dugme
// "yapilandirilmadi" der (DriveUploader.isConfigured). Kurulum:
// drive-sync/README.md
// >>> OPTIONAL: COPY drive.properties.example TO drive.properties AND ENTER YOUR OWN APPS SCRIPT URL + WRITE KEY.
// >>> ISTEGE BAGLI: drive.properties.example DOSYASINI drive.properties OLARAK KOPYALAYIP KENDI URL VE YAZMA ANAHTARINIZI GIRIN.
val drivePropsFile = rootProject.file("drive.properties")
val driveProps = Properties().apply {
    if (drivePropsFile.exists()) drivePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.example.ex30telemetry"
    compileSdk = 35

    defaultConfig {
        // >>> CHANGE THIS TO YOUR OWN UNIQUE PACKAGE NAME (e.g. io.github.yourname.ex30telemetry).
        // >>> BUNU KENDI BENZERSIZ PAKET ADINIZLA DEGISTIRIN. Google Play "com.example" ile baslayan adlari kabul etmez.
        applicationId = "com.example.ex30telemetry"
        minSdk = 29
        targetSdk = 35
        // Play'de basarisiz bir yukleme denemesi bile surum kodunu kalici tuketir;
        // her yeni yukleme icin artir.
        versionCode = 11
        versionName = "0.7.2"

        buildConfigField("String", "DRIVE_URL", "\"${driveProps.getProperty("driveUrl", "")}\"")
        buildConfigField("String", "DRIVE_SECRET", "\"${driveProps.getProperty("driveSecret", "")}\"")
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // Debug'a ozel kancalari (JourneyDebugHooks, CalibrationLogger) ayirmak icin.
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.car.app:app-automotive:1.4.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar stub'undaki org.json yalnizca istisna atan govdelerden olusuyor;
    // gercek gerceklestirim testlerde onu golgeliyor (Trip kalicilik testleri).
    testImplementation("org.json:json:20240303")
}
