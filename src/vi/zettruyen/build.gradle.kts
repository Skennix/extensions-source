import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ZetTruyen"
    versionCode = 13
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "vi"
        // Do 2026-09-05: zettruyen.work da 301 sang zettruyen1.com, giu nguyen duong dan
        // (/robots.txt -> /robots.txt), tuc domain cu khong con noi dung rieng. Nguon van chay
        // NHUNG chi nho redirect - tat redirect la chet im lang. Khai danh sach de nguoi dung tu
        // xoay khi ten mien dau chet, thay vi hang mot chuoi.
        baseUrl {
            mirrors(
                "https://www.zettruyen1.com",
                "https://www.zettruyen.work",
            )
        }
    }

    deeplink {
        path("/truyen-tranh/..*")
    }
}
