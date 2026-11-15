# Forbidden Streams VOD

<img src="app/src/main/res/drawable-nodpi/logo.jpg" width="280" alt="Forbidden Streams VOD logo">

*plug in the revolution*

An Android TV app (also runs on phones and tablets) for watching **movies and series** from an IPTV provider that uses an **Xtream Codes** login (server URL, username and password).

> This app is a player only. It doesn't include, host or link to any content. You bring your own provider login, and you're responsible for using a provider you're licensed to use.

## Features (v0.1)

- **Sign in** with an Xtream Codes server, username and password. You stay signed in on the device.
- **Movies** and **Series** tabs with provider categories, poster grids, ratings and search inside a category.
- **Movie details**: backdrop, plot, year, runtime, genre, director, cast.
- **Series details**: season picker and episode list with thumbnails, runtimes and progress bars.
- **Playback** with ExoPlayer (Media3): MP4, MKV, TS and HLS, plus audio and subtitle track selection.
- **Resume watching**: progress is saved every 10 seconds and when you leave the player. A title is cleared once you've watched 95% of it.
- **My List**: a *Continue watching* row plus your **Favorites** (movies and series).
- **Built for Android TV**: logo banner in the TV launcher, a side menu you reach with the D-pad, green focus rings on everything, larger posters, and remote media keys in the player.
- **Branded look**: toxic green, prohibition red and dark teal taken from the logo.
- Favorites and progress are stored **per account**, so switching providers doesn't mix them.

## Build

### Android Studio
1. Open the project folder in Android Studio (Ladybug or newer).
2. Let Gradle sync, then run the `app` configuration on a phone, an emulator or an Android TV device.

### Command line
```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions
Every push builds a debug APK. Open the **Actions** tab, pick the latest run, and download **ForbiddenStreamsVOD-debug** under *Artifacts*.

### Installing on Android TV / Fire TV
Turn on developer options and USB/network debugging on the TV, then:
```bash
adb connect <tv-ip-address>
adb install -r app-debug.apk
```

## Project layout

```
app/src/main/java/com/forbiddenstreams/vod/
├── VodApp.kt                 Application + simple dependency container
├── MainActivity.kt           Navigation (login → home → movie/series details)
├── data/
│   ├── Models.kt             App models (Movie, Series, Episode, ResumeEntry…)
│   ├── XtreamClient.kt       player_api.php client, lenient JSON parsing, stream URLs
│   ├── XtreamRepository.kt   In-memory cache for categories and lists
│   └── LocalStore.kt         DataStore: account, favorites, resume positions
├── player/PlayerActivity.kt  ExoPlayer screen, saves progress
└── ui/
    ├── Theme.kt, Common.kt   Theme, poster cards, focus highlight, helpers
    ├── Playback.kt           Starts the player for a movie/episode
    ├── login/                Sign-in screen
    ├── home/                 Movies / Series / My List tabs
    └── detail/               Movie and series detail screens
```

## Xtream Codes API used

| Call | Purpose |
|---|---|
| `player_api.php?username=…&password=…` | Sign in / account status |
| `action=get_vod_categories` / `get_vod_streams&category_id=` | Movie categories and lists |
| `action=get_vod_info&vod_id=` | Movie details |
| `action=get_series_categories` / `get_series&category_id=` | Series categories and lists |
| `action=get_series_info&series_id=` | Seasons and episodes |
| `/movie/{user}/{pass}/{id}.{ext}` | Movie stream |
| `/series/{user}/{pass}/{id}.{ext}` | Episode stream |

Provider panels are inconsistent (numbers sent as strings, empty objects sent as `[]`, missing fields), so `XtreamClient` parses everything leniently.

## Notes

- Many providers only serve plain HTTP, so cleartext traffic is allowed (`res/xml/network_security_config.xml`).
- Some providers block unknown players by user agent. If streams fail with HTTP 403, try changing `USER_AGENT` in `XtreamClient.kt`.
- The login is stored unencrypted in app-private storage.

## Ideas for next versions

- Global search across all categories
- Auto-play next episode
- Multiple saved provider accounts with a switcher
- Live TV + EPG
- Trailer playback (`youtube_trailer` field)
