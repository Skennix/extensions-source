import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Top Truyen"
    versionCode = 36
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"
    theme = "wpcomics"

    source {
        lang = "vi"
        // Do 2026-09-05: toptruyenzone10 da 301 sang toptruyenzone11. Hau to so tang dan la dau
        // hieu site xoay ten mien lien tuc (zone10 -> zone11 -> ...), nen khai danh sach thay vi
        // hang mot chuoi - lan sau chi them mot dong, khong phai di tim lai tu dau.
        baseUrl {
            mirrors(
                "https://www.toptruyenzone11.com",
                "https://www.toptruyenzone10.com",
            )
        }
    }
}
