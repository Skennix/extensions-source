import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NetTruyenTV (unoriginal)"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"
    theme = "wpcomics"

    source {
        lang = "vi"
        baseUrl = "https://nettruyentv.net"
    }
}
