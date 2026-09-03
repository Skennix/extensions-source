import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ComicVN"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "vi"
        // Site doi ten mien lien tuc: comicvn3 -> comicvn17 -> comicvn19.
        // Khai danh sach de nguoi dung tu xoay khi ten mien dau chet,
        // thay vi hang mot chuoi roi nguon chet im lang.
        baseUrl {
            mirrors(
                "https://comicvn19.net",
                "https://comicvn17.xyz",
            )
        }
    }
}
