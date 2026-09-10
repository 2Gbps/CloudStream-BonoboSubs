// Use an integer for version numbers
version = 14

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "Renegade Immortal (Xian Ni) series and its movie, 4K + 1080p HEVC, direct from BonoboSubs (v1.4.0)"
    authors = listOf("2Gbps")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie")

    language = "en"

    iconUrl = "https://raw.githubusercontent.com/2Gbps/CloudStream-BonoboSubs/main/logo.png"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
