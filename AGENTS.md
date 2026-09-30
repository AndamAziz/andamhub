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
