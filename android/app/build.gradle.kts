plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: CI decodes the keystore from a secret and passes it in through the environment.
// Every release must be signed with the same key, or phones refuse the update.
val ksFile = System.getenv("TAVERN_KEYSTORE")?.let { file(it) }

// Where the app looks for latest.json. Forks point this at their own repository.
val manifestUrl = System.getenv("TAVERN_MANIFEST_URL")
    ?: "https://github.com/HRA1011XW99/chiyao-tavern/releases/download/manifest/latest.json"

android {
    namespace = "xyz.chiyao.tavern"
    compileSdk = 36

    defaultConfig {
        applicationId = "xyz.chiyao.tavern"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("TAVERN_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("TAVERN_VERSION_NAME") ?: "1.0.0"
        buildConfigField("String", "MANIFEST_URL", "\"$manifestUrl\"")
    }

    signingConfigs {
        if (ksFile != null && ksFile.exists()) {
            create("release") {
                storeFile = ksFile
                storePassword = System.getenv("TAVERN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TAVERN_KEY_ALIAS")
                keyPassword = System.getenv("TAVERN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            ndk { abiFilters += listOf("arm64-v8a") }
        }
        debug {
            // the emulator build also carries x86_64 (tools/natives.mjs --x86_64)
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
    }
    buildFeatures { buildConfig = true }

    // Node and git are cross-compiled executables (tools/natives.mjs) disguised as lib*.so:
    // since Android 10 an app may only execute files in nativeLibraryDir, and they only land there unpacked.
    sourceSets["main"].jniLibs.srcDir(rootProject.file("build/natives/jniLibs"))
    sourceSets["main"].assets.srcDir(rootProject.file("build/natives/assets"))
    packaging { jniLibs { useLegacyPackaging = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // android.jar only has stubs of org.json; unit tests need the real thing
    testImplementation("org.json:json:20240303")
}
