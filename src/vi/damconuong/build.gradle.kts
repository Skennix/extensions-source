import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "DamCoNuong"
    versionCode = 17
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "vi"
        // Do 2026-09-28: damconuong.sbs 301 sang damconuong.name (site viet lai hoan toan tren mot
        // nen tang doc truyen moi - HTML/CSS/JS deu khac, con API rieng o api.damconuong.pw). Domain
        // cu khong con noi dung rieng, chi song nho redirect - giu lam mirror de nguoi dung tu xoay
        // khi domain moi chet, khong hang mot chuoi.
        baseUrl {
            mirrors(
                "https://damconuong.win",
                "https://damconuong.name",
                "https://damconuong.sbs",
            )
        }
    }

    deeplink {
        path("/truyen/..*")
    }
}
