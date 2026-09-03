import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ManhuaVN"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "vi"
        baseUrl {
            // Do 2026-09-03: thuong hieu that la manhuavn.top nhung domain do KHONG vao duoc
            // tu VN (DNS ra IP Cloudflare hop le, 3/3 lan ket noi tra 000, trong khi
            // manhuavn2.com cung dai Cloudflare tra 200 cung luc) => chi khai mirror song.
            // Dung custom() de user tu doi khi site xoay domain, khong can build lai.
            custom("https://manhuavn2.com")
        }
    }

    deeplink {
        path("/..*-0.html")
        path("/doc-truyen/..*")
    }
}
