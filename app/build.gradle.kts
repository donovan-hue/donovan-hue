import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.hifiplayer.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.hifiplayer"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // Versionado honesto: 0.17.x corresponde a las fases 1-17 del plan, todas implementadas y
        // verificadas por compilador y pruebas.
        //
        // 0.17.1 corrige un fallo que hizo inarrancable la 0.17.0: el manifest nombraba los
        // componentes con el namespace del módulo (com.hifiplayer.app.HiFiPlayerApp) en vez del
        // paquete real de las clases (com.hifiplayer.HiFiPlayerApp). Compilaba, se instalaba y se
        // cerraba al abrir. Lo detectó scripts/verify-apk.sh sobre el APK ya publicado, y ahora se
        // ejecuta en CI antes de subir los artefactos. La 1.0.0 no es una fase más: se alcanza cuando el
        // pliego esté además probado en dispositivos reales (DAC USB, bit-perfect, Android Auto,
        // auriculares Bluetooth), cosa que CI no puede hacer.
        //
        // Al desplegar desde una etiqueta (v1.2.3) el workflow pasa -PversionNameOverride=v1.2.3,
        // de modo que el APK publicado y la etiqueta no pueden discrepar.
        val versionOverride = (project.findProperty("versionNameOverride") as String?)?.removePrefix("v")
        versionCode = 20
        versionName = versionOverride ?: "0.17.3-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Clave de DEPURACIÓN estable, versionada a propósito en `ci/`.
        //
        // Por qué: el keystore de depuración que Android Gradle Plugin genera solo vive en la máquina
        // que compila, así que cada ejecución de CI producía una clave distinta. Consecuencia real:
        // la versión nueva no se podía instalar encima de la anterior ("Aplicación no instalada") y
        // había que desinstalar, perdiendo ajustes y biblioteca. Con esta clave, todas las compilaciones
        // firman igual y las actualizaciones entran sin tocar nada.
        //
        // No es un secreto y no sirve para publicar en una tienda: las credenciales de producción, si
        // existen, siguen teniendo prioridad en la configuración "release" de aquí abajo.
        val ciDebugKeystore = rootProject.file("ci/hifi-debug.keystore")
        if (ciDebugKeystore.exists()) {
            getByName("debug") {
                storeFile = ciDebugKeystore
                storePassword = "hifiplayer"
                keyAlias = "hifidebug"
                keyPassword = "hifiplayer"
            }
        }

        create("release") {
            // Never hardcode secrets: read from keystore.properties (git-ignored) or CI env vars.
            val propsFile = rootProject.file("keystore.properties")
            val props = Properties().apply { if (propsFile.exists()) propsFile.inputStream().use { load(it) } }
            val storePath = props.getProperty("storeFile") ?: System.getenv("HIFI_KEYSTORE_FILE")
            val storePass = props.getProperty("storePassword") ?: System.getenv("HIFI_KEYSTORE_PASSWORD")
            val aliasName = props.getProperty("keyAlias") ?: System.getenv("HIFI_KEY_ALIAS")
            val keyPass = props.getProperty("keyPassword") ?: System.getenv("HIFI_KEY_PASSWORD")
            if (storePath != null && storePass != null && aliasName != null && keyPass != null) {
                storeFile = file(storePath)
                storePassword = storePass
                keyAlias = aliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Only install the release signing config when real credentials are present,
            // so `assembleRelease` still works on CI without a keystore (unsigned artifact).
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/versions/**",
                "**/module-info.class",
            )
        }
    }

    testOptions {
        unitTests {
            // Robolectric necesita los recursos y el manifest fusionado para arrancar la app igual
            // que en un dispositivo.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true

            all {
                // Una sola máquina virtual de test, con memoria acotada: el mismo límite que usa el
                // resto del proyecto. Robolectric levanta un JVM aparte y aquí la memoria es escasa.
                it.maxHeapSize = "640m"
                it.jvmArgs("-XX:MaxMetaspaceSize=320m")
            }
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = false
        checkReleaseBuilds = false
    }

    bundle {
        language { enableSplit = false } // keep both locales in the base module
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xjvm-default=all")
    }
}

dependencies {
    implementation(project(":domain:model"))
    implementation(project(":domain:repository"))
    implementation(project(":domain:usecase"))
    implementation(project(":data:local"))
    implementation(project(":data:metadata"))
    implementation(project(":data:audio"))
    implementation(project(":data:repository"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:database"))
    implementation(project(":core:storage"))
    implementation(project(":core:metadata"))
    implementation(project(":core:permissions"))
    implementation(project(":core:usb"))
    implementation(project(":core:audio"))
    implementation(project(":native:dsp"))
    implementation(project(":native:audio_engine"))
    implementation(project(":presentation:navigation"))
    implementation(project(":presentation:library"))
    implementation(project(":presentation:playback"))
    implementation(project(":presentation:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.media3.session)
    implementation(libs.media3.exoplayer)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
