import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ───────────────────────── signing ─────────────────────────
// TaskMan runs with root, so its signature decides who may ever "update" it: sign it with YOUR OWN private key.
// Values come from environment variables (GitHub Actions secrets) or from keystore.properties
// (git-ignored; dev/make-keystore.sh creates both the key and that file).
// Without a key the build still succeeds and is signed with the temporary debug key: the APK installs,
// but it cannot be updated over a build that was signed with another key.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() }

val keystoreFile: File? = signingValue("TASKMAN_KEYSTORE", "storeFile")?.let { rootProject.file(it) }
val storePass: String? = signingValue("TASKMAN_STORE_PASSWORD", "storePassword")
val keyAliasName: String? = signingValue("TASKMAN_KEY_ALIAS", "keyAlias")
val keyPass: String? = signingValue("TASKMAN_KEY_PASSWORD", "keyPassword") ?: storePass
val hasPrivateKey: Boolean =
    keystoreFile?.isFile == true && storePass != null && keyAliasName != null && keyPass != null

if (!hasPrivateKey) {
    logger.warn("TaskMan: no private signing key is configured, APKs are signed with the temporary debug key (see README)")
}
// ───────────────────────── end of signing ─────────────────────────

android {
    namespace = "com.taskman.root"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.taskman.root"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasPrivateKey) {
            create("taskman") {
                storeFile = keystoreFile
                storePassword = storePass
                keyAlias = keyAliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            if (hasPrivateKey) signingConfig = signingConfigs.getByName("taskman")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // never unsigned: without a private key the release APK uses the debug key (installable, see above)
            signingConfig = signingConfigs.getByName(if (hasPrivateKey) "taskman" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
