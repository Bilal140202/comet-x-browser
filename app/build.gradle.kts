import java.security.SecureRandom
import java.util.Properties

// Comet-X app module
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release keystore is generated locally on first build (gitignored, never committed).
// This keeps the repo free of signing material while producing an installable release APK.
val keystoreDir = file("keystore")
val keystoreFile = File(keystoreDir, "cometx-release.jks")
val ksPropsFile = File(keystoreDir, "keystore.properties")

if (!keystoreFile.exists()) {
    keystoreDir.mkdirs()
    val charset = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"
    val pass = SecureRandom().let { rnd ->
        (1..24).map { charset[rnd.nextInt(charset.length)] }.joinToString("")
    }
    ksPropsFile.writeText("storePassword=$pass\nkeyPassword=$pass\nkeyAlias=cometx\n")
    val cmd = arrayOf(
        "keytool", "-genkeypair", "-v",
        "-keystore", keystoreFile.absolutePath,
        "-alias", "cometx",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-storepass", pass, "-keypass", pass,
        "-dname", "CN=Comet-X, OU=Comet-X, O=Comet-X, L=Ahmedabad, ST=Gujarat, C=IN"
    )
    val rc = ProcessBuilder(*cmd).redirectErrorStream(true).start().waitFor()
    require(rc == 0) { "keytool failed to generate release keystore" }
}

android {
    namespace = "com.cometx.browser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cometx.browser"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = keystoreFile
            if (ksPropsFile.exists()) {
                val p = Properties().apply { ksPropsFile.inputStream().use { load(it) } }
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("failed")
    }
}

tasks.matching { it.name.startsWith("validateSigningRelease") }.configureEach {
    // keystore is generated at configuration time (see top of file)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
