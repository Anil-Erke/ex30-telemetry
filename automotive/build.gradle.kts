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

// Google OAuth istemcisi (arac, "TVs and Limited Input devices") — depoya GIRMEZ.
// Bu bir kullanici parolasi DEGIL: Google cihaz uygulamalarinda istemci
// sirrini gizli saymiyor; erisim her kullanicinin kendi aracinda duran
// token'iyla (google/GoogleAuth.kt). Dosya yoksa alanlar bos kalir, uygulama
// derlenir ve "Google hesabi" satiri "istemci kimligi yok" der.
//
// 2026-09-29'a kadar burada drive.properties (Apps Script adresi + YAZMA
// anahtari) vardi; o anahtar herkesin yolculugunu TEK bir Drive'a yazdiriyordu.
//
// >>> OPTIONAL: COPY oauth.properties.example TO oauth.properties AND ENTER YOUR OWN GOOGLE OAUTH CLIENT.
// >>> ISTEGE BAGLI: oauth.properties.example DOSYASINI oauth.properties OLARAK KOPYALAYIP KENDI GOOGLE OAUTH ISTEMCINIZI GIRIN.
// Kurulum: README.md -> "Google Drive sync".
val oauthPropsFile = rootProject.file("oauth.properties")
val oauthProps = Properties().apply {
    if (oauthPropsFile.exists()) oauthPropsFile.inputStream().use { load(it) }
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
        versionCode = 14
        versionName = "0.8.2"

        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${oauthProps.getProperty("carClientId", "")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", "\"${oauthProps.getProperty("carClientSecret", "")}\"")
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
