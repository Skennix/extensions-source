import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MinoTruyen"
    versionCode = 6
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    deeplink {
        path("/.*")
    }

    source {
        name = "MinoTruyen Manga"
        lang = "vi"
        baseUrl {
            custom("https://minotruyenv7.xyz")
        }
    }

    source {
        name = "MinoTruyen Comics"
        lang = "vi"
        baseUrl {
            custom("https://minotruyenv7.xyz")
        }
    }

    source {
        name = "MinoTruyen Hentai"
        lang = "vi"
        baseUrl {
            custom("https://minotruyenv7.xyz")
        }
    }
}

dependencies {
    implementation(project(":lib:cryptoaes"))
}
