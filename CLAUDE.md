# heute-nachrichten

Tools for pulling video URLs out of the ZDF Mediathek, starting with the
**"heute 19 Uhr"** broadcast page:
<https://www.zdf.de/magazine/heute-19-uhr-102>

This project contains both a **Python CLI extractor** (`zdf_heute_url.py`) and a **native
Android app** (`app/`) that ports it — see the two sections below.

## `zdf_heute_url.py` — extract the first entry's video URL

Resolves the most recent episode on a ZDF magazine page to a directly usable
stream URL. **Python 3, stdlib only** (no `pip install`, no headless browser).

### Usage
```bash
python3 zdf_heute_url.py                 # best progressive MP4 URL (default)
python3 zdf_heute_url.py --hls           # adaptive HLS master .m3u8 instead
python3 zdf_heute_url.py --all           # every stream URL (type / quality / url)
python3 zdf_heute_url.py --json          # metadata + all streams as JSON
python3 zdf_heute_url.py --dgs           # sign-language (DGS) variant
python3 zdf_heute_url.py <other-zdf-url> # any /magazine/... page
python3 zdf_heute_url.py --player-id ngplayer_2_3   # different player profile
```

- The resolved URL goes to **stdout** (clean, pipeable); the episode title and
  page link go to **stderr** as `#` comment lines. So this just works:
  ```bash
  python3 zdf_heute_url.py | xargs yt-dlp        # or: curl -O, ffmpeg -i, mpv
  ```
- Exit code is non-zero with an `error:` line on stderr if anything fails.

### How it works (verified against the live site, June 2026)
No decompilation or auth bypass — everything below is data the page hands a
normal browser:

1. **Fetch the page HTML.** ZDF server-renders all episode data as escaped JSON
   (React Flight payload) directly in the page — no JS execution needed.
2. **Scrape the player token** from that JSON: `videoToken.apiToken`. It is
   **short-lived** (~2 days), so it is read fresh on every run, never hardcoded.
3. **Find the first `contentType:"EPISODE"` node** (the newest broadcast). Each
   episode lists one or more media variants, each with:
   - `ptmdTemplate` — e.g. `/tmd/2/{playerId}/vod/ptmd/mediathek/<id>/<v>`
   - `vodMediaType` — `DEFAULT` (normal broadcast) · `DGS` (sign language) · …
   The script picks `DEFAULT` (or `DGS` with `--dgs`).
4. **Resolve the PTMD** (Player Targeting Meta Data):
   ```
   GET https://api.zdf.de/tmd/2/<playerId>/vod/ptmd/mediathek/<id>/<v>
   Header: Api-Auth: Bearer <apiToken>
   ```
   `playerId` defaults to `android_native_5` (returns the widest format set;
   `ngplayer_2_3` returns fewer).
5. **Walk the response**: `priorityList[].formitaeten[].qualities[].audio.tracks[].uri`,
   keeping the `class:"main"` German audio track. Available outputs:
   - **HLS** (`application/x-mpegURL`, `.m3u8`) — adaptive, `auto` master + per-quality
   - **Progressive MP4** (`video/mp4`) — `fhd` (~6628k/1080p) · `hd` · `veryhigh` · `high` · `low`
   - **WebM** (`video/webm`) — `fhd` · `hd` · `veryhigh` · `high`
   Default output = best progressive MP4 (`fhd`); `--hls` = the `auto` master.

### Implementation notes / gotchas
- **Episode regex must stay inside one JSON node** (`[^}]*?`, *not* `.*?`/DOTALL).
  Clip teasers ("Aus der Sendung") appear before the episode list; a greedy match
  stretches a clip's title across to a later `EPISODE` marker and resolves the
  wrong video. This was a real bug caught in testing.
- Hosts seen: `nrodlzdf-a.akamaihd.net` / `rodlzdf-a.akamaihd.net` (progressive),
  `zdfvod.akamaized.net` (HLS). Stream URLs themselves are unauthenticated; only
  the PTMD lookup needs the bearer token.
- The same flow works for other ZDF `/magazine/...` pages — pass the URL as the
  positional arg.

> Stays within the workspace clean-room rules: black-box use of public HTTP
> endpoints and page-embedded data only.

## Android app — `Heute Nachrichten`

Native Jetpack Compose app that lists the latest few **ZDF "heute 19 Uhr"** broadcasts and, on
tap, opens the resolved video stream in an external player (**VLC** or **mpv**). It is a Kotlin
port of `zdf_heute_url.py` (above), scaffolded from the workspace `android-basic` template. Same
clean-room posture: black-box use of ZDF's public HTTP endpoints and page-embedded data only.

### Key versions
- AGP `8.9.1` / Kotlin `2.1.0` / Compose BOM `2025.03.01`
- compileSdk `35` / minSdk `26` / targetSdk `35` / jvmTarget `11` / Gradle `8.11.1`
- Added libs: `kotlinx-coroutines-android`, `lifecycle-viewmodel-compose`,
  `lifecycle-runtime-compose`. JSON via the platform's bundled `org.json` (no dependency);
  HTTP via `HttpURLConnection`. `org.json:json` is a **test-only** dep (the platform one is
  stubbed in JVM unit tests).

### Networking posture
Unlike the offline `android-basic` template, this app **declares `INTERNET`** and a
**`<queries>`** block for `org.videolan.vlc` / `is.xyz.mpv` + a `video/*` `ACTION_VIEW` intent
(mandatory on API 30+ for player detection / `setPackage` to work). See
`app/src/main/AndroidManifest.xml`.

### Structure
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

The port reuses the script's two parsing gotchas: the episode regex stays within one JSON node
(`[^}]*?`), and because `vodMediaType` **follows** `ptmdTemplate` within a node, each
`ptmdTemplate` is paired with the `vodMediaType` read forward only to the next `}`.

### Build / test
```bash
./gradlew testDebugUnitTest          # offline parser tests (fixtures in app/src/test/resources/)
./gradlew assembleDebug              # debug APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug assembleRelease  # lint + R8-minified release build
```
`.github/workflows/build.yml` runs unit tests + a release APK build on every push. Manual:
install on a device with VLC/mpv, launch → tap a broadcast → the player opens. Cross-check the
top episode's URL against `python3 zdf_heute_url.py`.

### Local release — `scripts/release.sh`
`./scripts/release.sh <keystore-password>` stops the Gradle daemon, runs `assembleDebug`, then
signs the APK with `apksigner`, writing `heute-nachrichten-signed.apk` to the project root.
Prerequisites:
1. **`apksigner` on PATH** (ships with SDK build-tools):
   ```bash
   export PATH="$PATH:$ANDROID_HOME/build-tools/$(ls $ANDROID_HOME/build-tools | tail -1)"
   ```
2. **Keystore at `../my-debug.jks`** (one level above the project root, shared across workspace
   projects). Create once if it doesn't exist:
   ```bash
   keytool -genkey -v -keystore ../my-debug.jks \
     -alias my-key -keyalg RSA -keysize 2048 -validity 10000 \
     -storepass <password> -keypass <password> -dname "CN=Dev, O=Dev, C=US"
   ```
