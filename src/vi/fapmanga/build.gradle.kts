import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "FapManga"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "vi"
        baseUrl = "https://fapmanga.com"
    }
}

android {
    sourceSets {
        named("test") {
            kotlin.setSrcDirs(listOf("test/kotlin"))
        }
    }
}

// KSP (compiler) doi hoi dung 1 lop @Source moi compilation unit, nhung unit test
// khong tham chieu lai lop do => phai tat ksp rieng cho variant unit test.
tasks.matching { it.name == "kspDebugUnitTestKotlin" }.configureEach { enabled = false }

dependencies {
    testImplementation(libs.bundles.common)
    testImplementation(libs.junit)
}
