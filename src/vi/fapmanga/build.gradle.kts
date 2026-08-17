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
            // ExtensionPlugin của repo gán trọn thư mục "src" làm sourceSet MAIN, nên "src/test"
            // không bao giờ tồn tại — nó bị main "nuốt" trước. Vì vậy phải trỏ thẳng ra
            // "test/kotlin" (ngang hàng với "src"), không phải "src/test".
            // Xoá dòng này: Gradle không báo lỗi, chỉ im lặng không tìm thấy file .kt nào
            // (NO-SOURCE) => testDebugUnitTest vẫn báo XANH dù không chạy test nào cả.
            kotlin.setSrcDirs(listOf("test/kotlin"))
            // Đường nạp fixture (HTML/JSON mẫu cho các task parser sau này): đọc qua classpath
            // resource (getResourceAsStream) thay vì đọc file trực tiếp bằng đường dẫn hệ thống.
            resources.setSrcDirs(listOf("test/fixtures"))
        }
    }
}

// KSP (compiler) đòi hỏi đúng 1 lớp @Source mỗi compilation unit (xem ExtensionPlugin.kt), nhưng
// unit test biên dịch lại toàn bộ module theo một variant riêng (debugUnitTest) mà không tạo lớp
// @Source mới cho variant đó. Nếu để KSP chạy ở đây, nó sẽ báo thiếu/trùng nguồn @Source và làm
// vỡ build test, trong khi lớp @Source thật sự chỉ cần được KSP xử lý ở variant debug/release
// (phục vụ assemble, không liên quan gì đến unit test). Tắt riêng cho unit test là đủ.
tasks.matching { it.name == "kspDebugUnitTestKotlin" }.configureEach { enabled = false }

// Spotless mặc định (SpotlessPlugin.kt trong build-logic) chỉ quét "src/**/*.kt" — đó là quy ước
// dùng chung cho MỌI module extension. Thư mục "test/kotlin" nằm ngoài "src" (xem lý do ở
// sourceSets bên trên) nên không được quét mặc định. Mở rộng riêng ở đây (không sửa build-logic
// dùng chung, chỉ ảnh hưởng module này) để code test cũng phải qua ktlint như code chính.
spotless {
    kotlin {
        target("src/**/*.kt", "test/kotlin/**/*.kt", "*.kts")
    }
}

dependencies {
    testImplementation(libs.bundles.common)
    testImplementation(libs.junit)
    // ExtensionPlugin.kt khai extensions-lib là "compileOnly" cho variant main (lib này được app
    // Tachiyomi/Mihon cung cấp lúc runtime, không được đóng gói vào apk extension). Trong AGP,
    // cấu hình "compileOnly" KHÔNG được unit-test variant kế thừa (chỉ "implementation"/"api" mới
    // được kế thừa), nên testCompileClasspath thiếu hẳn MangasPage/SManga/SChapter/Page/... Phải
    // khai lại ở đây bằng "testImplementation", dùng lại đúng logic chọn phiên bản theo
    // keiyoushi.libVersion như ExtensionPlugin.kt để không bao giờ lệch với lib dùng ở main.
    testImplementation(keiyoushi.libVersion.flatMap { if (it == "1.6") libs.tachiyomi.lib.v16 else libs.tachiyomi.lib.v14 })
}
