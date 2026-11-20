import java.util.Properties

plugins {
    id("com.android.application")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.sideviewvr"
    compileSdk = 36
    ndkVersion = "30.0.16248370"
    localProps.getProperty("ndk.path")?.let { ndkPath = it }

    defaultConfig {
        applicationId = "app.sideviewvr"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0-beta"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        resources.excludes += listOf("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**",
            "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST")
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
    // SMB2/3 client (Apache-2.0)
    implementation("com.hierynomus:smbj:0.13.0")
    testImplementation("junit:junit:4.13.2")
}
