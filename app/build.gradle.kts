plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---- spolocny blok pre vsetky appky (nemen len tu, viz docs/shared-standard.md) ----
/**
 * Pristupove udaje k NAS-u sa citaju z JEDNEHO suboru spolocneho pre vsetky
 * appky: nas-credentials.local. Odtial sa pri builde vlozia do APK ako
 * predvolby, takze na telefone sa uz nic nezadava.
 *
 * Prepis v Nastaveniach appky ma prednost — to je cesta pre zmenu hesla bez
 * rebuildu. Ked subor chyba, hodnoty ostanu prazdne a appka si udaje vypyta
 * v Nastaveniach ako predtym; build kvoli tomu nikdy nespadne.
 *
 * Heslo timto konci v APK. Je to vedome rozhodnutie: APK lezia na tom istom
 * NAS-e pod tym istym uctom, takze kto sa dostane k nim, ten ucet uz ma.
 */
fun nasSetting(key: String): String {
    val f = file(System.getenv("NAS_CREDENTIALS") ?: "nas-credentials.local")
    if (!f.exists()) return ""
    return f.readLines()
        .firstOrNull { it.trimStart().startsWith("$key=") }
        ?.substringAfter("=")?.trim().orEmpty()
}

/** Heslo moze obsahovat spatne lomitko aj uvodzovku — bez tohto by build nepreslo. */
fun nasField(key: String): String =
    "\"" + nasSetting(key).replace("\\", "\\\\").replace("\"", "\\\"") + "\""
// ---- koniec spolocneho bloku -------------------------------------------------

android {
    namespace = "sk.lukac.tankomat"
    compileSdk = 35

    defaultConfig {
        applicationId = "sk.lukac.tankomat"
        minSdk = 26
        targetSdk = 35
        // 1.9: jednotna paticka s verziou dole (docs/shared-standard.md 2.7).
        // 1.10 (16. 8. 2026): opravený výpočet vzdialeností k pumpám —
        // `st-km` zo stránky nie je vzdialenosť od centra mesta a nesmie sa
        // pripočítavať; vzdialenosti sú teraz namerané po ceste
        // (viď data/model/StationDistances.kt).
        // Vydanie 3. 9. 2026: pocitadlo prenesenych dat rozdelene na Wi-Fi
        // a mobilne (Prenos.kt, spolocny subor) a sirsi rozsah velkosti
        // pisma (-6 az +6 namiesto -1 az +3).
        // Vydanie 7. 9. 2026: ranne stiahnutie cien o 6:00 vyhradne po Wi-Fi
        // (RannaObnova.kt) a posledny sken sa uz neprepise prazdnym.
        // 1.20 (13. 10. 2026, ANDROID-5): ranne stiahnutie drzi 6:00 aj po
        // dni bez Wi-Fi (jednorazova praca namiesto periodickej) a cez simku
        // sa nespusti ani na „nemeranych" mobilnych datach.
        // 1.22 (28. 10. 2026, TANKOMAT-7): mbenzin.cz s osobnym klucom (data
        // mapy, 1 dopyt na palivo) + benzinmapa; zalozky benzin.cz /
        // benzinmapa / CZ ceny.
        // 1.23 (28. 10. 2026, TANKOMAT-8): benzinmapa prec (len odhady),
        // ostava benzin.cz — zalozky Porovnanie a CZ ceny.
        // 1.24 (28. 10. 2026, APPKY-4): pocitadlo dat z Androidu.
        // 1.25 (30. 10. 2026, TANKOMAT-9): ziadna vzdusna ciara — pumpy mimo
        // tabulky dostanu trasu autom z OSRM (drzi sa natrvalo v trasy.json),
        // okruh hladania plati po ceste.
        // 1.26 (30. 10. 2026, TANKOMAT-10): rucna oprava km aj v zalozke CZ ceny
        // — ten isty dialog a ten isty zapis (manualKm), plati v oboch zalozkach.
        versionCode = 27
        versionName = "1.26"

        buildConfigField("String", "NAS_HOST", nasField("NAS_HOST"))
        buildConfigField("String", "NAS_SHARE", nasField("NAS_SHARE"))
        buildConfigField("String", "NAS_USER", nasField("NAS_USER"))
        buildConfigField("String", "NAS_PASS", nasField("NAS_PASS"))
        // Osobny kluc k mbenzin.cz (28. 10. 2026) — v APK z rovnakeho dovodu
        // ako heslo k NAS-u; posiela sa len v hlavicke, nikdy v adrese.
        buildConfigField("String", "MBENZIN_KLIC", nasField("MBENZIN_KLIC"))
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
        buildConfig = true
    }

    sourceSets {
        named("main") {
            java.srcDirs("src/main/kotlin")
        }
        named("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Premenuje výstupné APK z generického "app-debug.apk" na "tankomat.apk" —
    // pevné meno kvôli self-update konvencii (NAS priečinok Tankomat/tankomat.apk,
    // súbor sa pri každom vydaní len prepíše; skutočnú verziu appka číta z APK
    // manifestu, nie z názvu súboru).
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "tankomat.apk"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.jsoup)

    implementation(libs.smbj)
    implementation(libs.slf4j.nop)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
