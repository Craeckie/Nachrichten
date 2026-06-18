# Heute Nachrichten (Android)

Native Android app that lists the latest few **ZDF "heute 19 Uhr"** broadcasts and, on tap,
opens the resolved video stream in an external player (**VLC** or **mpv**).

It is a Kotlin port of the repo's `../zdf_heute_url.py` extractor wrapped in a Jetpack Compose
UI. Scaffolded from the `android-basic` template. Stays within the workspace clean-room rules:
black-box use of ZDF's public HTTP endpoints and page-embedded data only — no decompilation.

## Key versions

- AGP `8.9.1` / Kotlin `2.1.0` / Compose BOM `2025.03.01`
- compileSdk `35` / minSdk `26` / targetSdk `35` / jvmTarget `11` / Gradle `8.11.1`
- Added libs: `kotlinx-coroutines-android`, `lifecycle-viewmodel-compose`,
  `lifecycle-runtime-compose`. JSON via the platform's bundled `org.json` (no dependency);
  HTTP via `HttpURLConnection`. `org.json:json` is a **test-only** dep (the platform one is
  stubbed in JVM unit tests).

## Networking posture

Unlike the offline template, this app **declares `INTERNET`** and a **`<queries>`** block for
`org.videolan.vlc` / `is.xyz.mpv` + a `video/*` `ACTION_VIEW` intent (mandatory on API 30+ for
player detection / `setPackage` to work). See `app/src/main/AndroidManifest.xml`.

## Structure

```
app/src/main/java/de/heute/nachrichten/
  MainActivity.kt                 # ComponentActivity → AppTheme { HomeScreen() }
  data/
    ZdfClient.kt                  # port of zdf_heute_url.py: fetchPage, extractApiToken,
                                  #   findEpisodes(N), fetchPtmd, collectStreams, pickBest*
    ZdfRepository.kt              # loadEpisodes() (1 page fetch) + lazy resolveStreamUrl()
  ui/
    HeuteViewModel.kt             # UiState Loading/Success/Error; PlayEvent one-shot effects
    HomeScreen.kt                 # episode list, refresh, per-card resolve spinner, snackbar
  player/PlayerLauncher.kt        # detect VLC/mpv → launch; else system chooser
```

### How resolution works (mirrors the Python script)

1. `loadEpisodes()` fetches the page once, scrapes the short-lived `apiToken` (cached in memory,
   never persisted — ~2 day TTL), and `findEpisodes()` regex-parses the escaped React-Flight
   JSON for the newest `EPISODE` nodes (DEFAULT media variant).
2. On tap, `resolveStreamUrl()` hits the PTMD API
   (`GET https://api.zdf.de<ptmdTemplate>` with `Api-Auth: Bearer <token>`), flattens the
   streams (main German audio), and picks the best progressive MP4 (HLS fallback).

### Parsing gotchas (carried from the Python original)

- **Episode regex stays within one JSON node** (`[^}]*?`, never greedy/DOTALL): otherwise a clip
  teaser's title bleeds onto a later EPISODE marker and the wrong video resolves.
- **vodMediaType FOLLOWS ptmdTemplate** inside a node (`{"__typename":"VodMedia","ptmdTemplate":…,"vodMediaType":…}`):
  `findEpisodes` pairs each `ptmdTemplate` with the `vodMediaType` in its own node by reading
  forward only to the next `}`. Verified against the live page.

## Build / test / verify

```bash
./gradlew testDebugUnitTest     # offline parser tests (fixtures in app/src/test/resources/)
./gradlew assembleDebug         # debug APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug assembleRelease   # lint + R8-minified release build
```

Manual: install on a device with VLC/mpv, launch → see the latest broadcasts → tap → player
opens. Cross-check the top episode's URL against `python3 ../zdf_heute_url.py`.

`.github/workflows/build.yml` runs unit tests + a release APK build on every push.
