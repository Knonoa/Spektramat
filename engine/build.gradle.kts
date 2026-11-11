plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// The film engine: the spektrafilm port (C++), the RAW readers, the tables the
// GPU pipeline reads, and the one text (src/main/shared/shaders/latent.glsl)
// that is both a shader and C++.
android {
    namespace = "com.spektra.latent.engine"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // shaders/latent.glsl ships as an asset and is also on the C++ include path
    sourceSets["main"].assets.srcDir("src/main/shared")
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
