# Andam — native Android app

Kotlin + Jetpack Compose app with a Media3 (ExoPlayer) player. It replaces the old
Capacitor WebView wrapper in `android-app/` and keeps the same application id
(`uk.andam.app`) and signing key, so it installs as an update.

- **Catalog:** the same backend as the website (`https://ip.andam.uk/api/public/*`):
  Live TV, Movies and Series from the Xtream providers, plus IPTV playlists.
- **Streams:** always through `/api/public/xtream-play` with the server's opaque tokens —
  provider URLs, credentials and M3U Referer/Origin/User-Agent headers stay on the server/relay.
- **Player:** ExoPlayer with FFmpeg audio (AC3/E-AC3/DTS), TS/HLS auto-detection, reconnect with
  back-off, stall watchdog, HLS fallback and the server audio fixer (`tc=1`) as last resort.
- **Accounts:** Supabase email/password, or Google via the Lovable OAuth broker
  (`lovable://oauth-callback`), or guest (IPTV only).

Built by `.github/workflows/android.yml` (Gradle 8.10, AGP 8.7, Kotlin 2.0, JDK 17).
