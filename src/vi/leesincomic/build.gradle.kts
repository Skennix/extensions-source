import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "LeeSinComic"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "vi"
        baseUrl = "https://leesincomic.com"
    }
}

android {
    sourceSets {
        named("test") {
            // ExtensionPlugin gán trọn thư mục "src" làm sourceSet MAIN, nên "src/test" bị main
            // "nuốt" trước và không bao giờ tồn tại. Phải trỏ thẳng ra "test/kotlin" (ngang hàng
            // với "src"). Xoá dòng này: Gradle KHÔNG báo lỗi, chỉ im lặng NO-SOURCE ⇒
            // testDebugUnitTest vẫn XANH dù không chạy ca nào.
            kotlin.setSrcDirs(listOf("test/kotlin"))
            // Fixture nạp qua classpath resource (getResourceAsStream), không đọc theo đường dẫn
            // hệ thống — đường dẫn hệ thống đổi theo chỗ chạy, classpath thì không.
            resources.setSrcDirs(listOf("test/fixtures"))
        }
    }
}

// KSP đòi đúng 1 lớp @Source mỗi compilation unit, nhưng unit test biên dịch lại module theo một
// variant riêng (debugUnitTest) mà không tạo lớp @Source cho variant đó ⇒ để KSP chạy ở đây là vỡ
// build test. Lớp @Source thật chỉ cần KSP xử lý ở debug/release (phục vụ assemble).
tasks.matching { it.name == "kspDebugUnitTestKotlin" }.configureEach { enabled = false }

// Spotless mặc định (build-logic) chỉ quét "src/**/*.kt". "test/kotlin" nằm ngoài "src" (xem lý do
// ở sourceSets) nên không được quét. Mở rộng RIÊNG ở module này, đừng sửa build-logic dùng chung.
spotless {
    kotlin {
        target("src/**/*.kt", "test/kotlin/**/*.kt", "*.kts")
    }
}

dependencies {
    testImplementation(libs.bundles.common)
    testImplementation(libs.junit)
    // ExtensionPlugin khai extensions-lib là "compileOnly" cho variant main (app Mihon cấp lúc
    // runtime). AGP KHÔNG cho unit-test variant kế thừa "compileOnly", nên testCompileClasspath
    // thiếu hẳn MangasPage/SManga/SChapter/Page. Khai lại ở đây, dùng ĐÚNG logic chọn phiên bản
    // theo keiyoushi.libVersion như ExtensionPlugin để không bao giờ lệch với lib dùng ở main.
    testImplementation(keiyoushi.libVersion.flatMap { if (it == "1.6") libs.tachiyomi.lib.v16 else libs.tachiyomi.lib.v14 })
}
