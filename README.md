# Forbidden Streams VOD

<img src="app/src/main/res/drawable-nodpi/logo.jpg" width="280" alt="Forbidden Streams VOD logo">

*plug in the revolution*

An Android TV app (also runs on phones and tablets) for watching **movies and series** from an IPTV provider that uses an **Xtream Codes** login (server URL, username and password).

> This app is a player only. It doesn't include, host or link to any content. You bring your own provider login, and you're responsible for using a provider you're licensed to use.

## Features (v0.1)

- **Sign in** with an Xtream Codes server, username and password. You stay signed in on the device.
- **Home screen** with two big poster buttons, **Movies** and **TV Series**, plus My List and Settings.
- **Download before browsing**: choosing Movies or TV Series downloads that catalog first, with a live title count, so the screen opens ready.
- **Info panel** at the top shows the focused title's name, year, runtime, genre, rating, plot and backdrop.
- **Smooth TV scrolling**: the focused poster glides to a fixed spot; rows remember their position.
- **Parental controls**: set a 4-digit PIN in Settings to hide anything marked 18+ / XXX (and adult categories) everywhere.
- **Netflix-style Movies and TV Series screens**: every provider category is its own sideways-scrolling row, with *Recently added* first. Hold OK (or long-press) on a poster to open its whole category full screen; Back closes it. Only the focused row shows under a large info panel.
- **Keep watching** row at the top of each screen. Selecting an item opens Resume / Start over / Remove.
- **Search**: the Search button opens a search screen; movies and TV series fill in as you type.
- **Movie details**: poster with the description beside it, a white Play button, and a **Trailers** row (the provider's trailer plus YouTube search results) that plays inside the app via YouTube's official player.
- **Series details**: season picker and episode list with thumbnails, runtimes and progress bars.
- **Playback** with ExoPlayer (Media3): MP4, MKV, TS and HLS, plus audio and subtitle track selection.
- **Resume watching**: progress is saved every 10 seconds and when you leave the player. A title is cleared once you've watched 95% of it.
- **My List**: Keep watching plus your **Favorites** (movies and series).
- **In-app updates**: Settings checks GitHub Releases for a newer build, downloads it and opens the installer. The Settings button shows a badge when an update is out.
- **Built for Android TV**: logo banner in the TV launcher, green focus rings on everything, larger posters, and remote media keys in the player.
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

### Download
Every push to `main` builds the app and publishes it as the **latest** release:

https://github.com/jaytatro/ForbiddenStreamsVOD/releases/latest/download/ForbiddenStreamsVOD.apk

The build number comes from the GitHub Actions run number, and the in-app updater compares it with the `(build N)` in the release title.

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
