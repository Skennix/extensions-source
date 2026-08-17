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
            // AGP mac dinh coi "src" la thu muc goc cua sourceSet MAIN (java/kotlin/res...),
            // nen "src/test" khong bao gio ton tai — no bi sourceSet main "nuot" truoc. Vi vay
            // phai tro thang ra "test/kotlin" (ngang hang voi "src"), khong phai "src/test".
            // Xoa dong nay: Gradle khong bao loi, chi im lang khong tim thay file .kt nao
            // (NO-SOURCE) => testDebugUnitTest van bao XANH du khong chay test nao ca.
            kotlin.setSrcDirs(listOf("test/kotlin"))
            // Duong nap fixture (HTML/JSON mau cho cac task parser sau nay): doc qua classpath
            // resource (getResourceAsStream) thay vi doc file truc tiep bang duong dan he thong.
            resources.setSrcDirs(listOf("test/fixtures"))
        }
    }
}

// KSP (compiler) doi hoi dung 1 lop @Source moi compilation unit (xem ExtensionPlugin.kt), nhung
// unit test bien dich lai toan bo module theo mot variant rieng (debugUnitTest) ma khong tao lop
// @Source moi cho variant do. Neu de KSP chay o day, no se bao thieu/trung nguon @Source va lam
// vo build test, trong khi lop @Source that su chi can duoc KSP xu ly o variant debug/release
// (phuc vu assemble, khong lien quan gi den unit test). Tat rieng cho unit test la du.
tasks.matching { it.name == "kspDebugUnitTestKotlin" }.configureEach { enabled = false }

// Spotless mac dinh (SpotlessPlugin.kt trong build-logic) chi quet "src/**/*.kt" — do la quy uoc
// dung chung cho MOI module extension. Thu muc "test/kotlin" nam ngoai "src" (xem ly do o
// sourceSets ben tren) nen khong duoc quet mac dinh. Mo rong rieng o day (khong sua build-logic
// dung chung, chi anh huong module nay) de code test cung phai qua ktlint nhu code chinh.
spotless {
    kotlin {
        target("src/**/*.kt", "test/kotlin/**/*.kt", "*.kts")
    }
}

dependencies {
    testImplementation(libs.bundles.common)
    testImplementation(libs.junit)
    // ExtensionPlugin.kt khai extensions-lib la "compileOnly" cho variant main (lib nay duoc app
    // Tachiyomi/Mihon cung cap luc runtime, khong duoc dong goi vao apk extension). Trong AGP,
    // cau hinh "compileOnly" KHONG duoc unit-test variant ke thua (chi "implementation"/"api" moi
    // duoc ke thua), nen testCompileClasspath thieu han MangasPage/SManga/SChapter/Page/... Phai
    // khai lai o day bang "testImplementation", dung lai dung logic chon phien ban theo
    // keiyoushi.libVersion nhu ExtensionPlugin.kt de khong bao gio lech voi lib dung o main.
    testImplementation(keiyoushi.libVersion.flatMap { if (it == "1.6") libs.tachiyomi.lib.v16 else libs.tachiyomi.lib.v14 })
}
