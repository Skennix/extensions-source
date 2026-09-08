import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hắc Ám Chi Các"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"
    theme = "initmanga"

    source {
        lang = "vi"
        baseUrl {
            custom("https://haccbl.xyz")
        }
    }
}
