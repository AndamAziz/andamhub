<!-- LOVABLE:BEGIN -->
> [!IMPORTANT]
> This project is connected to [Lovable](https://lovable.dev). Avoid rewriting
> published git history — force pushing, or rebasing/amending/squashing commits
> that are already pushed — as it rewrites history on Lovable's side and the
> user will likely lose their project history.
>
> Commits you push to the connected branch sync back to Lovable and show up in
> the editor, so keep the branch in a working state.
<!-- LOVABLE:END -->

- Android CI publishes APK/AAB downloads through GitHub Releases, not Actions Artifacts, because artifact-storage quotas can block otherwise successful builds.
- Android OAuth uses Lovable's allowed `lovable://oauth-callback` deep link with state validation and Capacitor Browser/App plugins so social sign-in returns to the installed app.
- Android player fullscreen portals the existing video stage to the document body so WebView rotation cannot trap it inside the page layout.
- Live TV visual work in `public/andam.html` must remain presentation-only; preserve playback engines, relay/proxy calls, authentication, data sources, and routes.
- M3U request headers stay inside encrypted playback tokens and are allowlisted server-side, because protected channels must work without exposing provider metadata.
- Exception: `/api/public/iptv?action=play` also returns `direct` {url, headers} (every channel of a public playlist, and header-protected channels of any playlist) so the native Android app (`android-native/`) can request them itself; Xtream provider URLs and credentials are never exposed.
- The native app (`android-native/`) has a TV layout (`ui/Tv.kt`, `LocalTv`): left menu, category pane, search button instead of a text field, focus rings via `tvFocus`; the player maps remote keys in `PlayerActivity.dispatchKeyEvent`. Keep new clickable UI focusable with a visible focus ring.
- Windows app: `windows-app/` (Electron) opens https://ip.andam.uk in its own window; `.github/workflows/windows.yml` builds `Andam-Setup.exe` on windows-latest and republishes the rolling **pre-release** `windows-latest` (never "latest", so the Android updater keeps seeing Android builds). The app auto-updates from that release via electron-updater (generic provider).
- Windows app plays through a bundled mpv ("Andam Player", `windows-app/mpv/`, downloaded in CI by `scripts/get-player.js`). The website detects `window.andamDesktop.player` and hands streams to it in `lvPlay` (deskPlay); "Play here instead" / `localStorage andam.deskPlayer=0` keeps the in-page player. `/api/public/download?app=android|windows` redirects to the newest installer.
- Exception 2: providers listed in `DEVICE_DIRECT_HOSTS` (xtream.ts; currently myrestreamer.com, which blocks the relay IP since 2026-10-01) also return `direct` provider links from `play`/`series_info` when the caller passes `device=1` (Android app, Windows app). Apps try the relay first and fall back to the device route (Android `DirectRoute`, website `deskDirectPref`), re-checking the relay every 30 min. In a normal browser the website opens that link in the browser's own player in a new tab (webDirectCard), since an https page cannot load http provider streams.
