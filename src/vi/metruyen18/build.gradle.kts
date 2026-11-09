import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MeTruyen18"
    versionCode = 63
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "vi"
        baseUrl {
            // Do 2026-10-26: metruyen18.vip 200 -> redirect sang damconuong.win (cung site, cung API
            // api.damconuong.pw) nen dung lai ma DamCoNuong. Khai damconuong.win lam goc de header
            // Referer/Origin giong ban damconuong da chay duoc.
            mirrors(
                "https://damconuong.win",
                "https://metruyen18.vip",
            )
        }
    }

    deeplink {
        path("/truyen/.*")
    }
}
