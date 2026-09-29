# Stable APK player and stream compatibility

## What will change
- Replace the heavy channel/settings overlays with a lighter player interface that opens quickly and avoids cloning or repainting hundreds of channel rows.
- Keep the active video element alive while menus open, close, or enter fullscreen, and limit recovery work so failed channels cannot create restart loops that crash the APK.
- Make channel menus render in small batches, retain category/search access, and keep controls touch-friendly and consistent on mobile, laptop, and TV.
- Support M3U channels that specify required `Referer`, `Origin`, or `User-Agent` headers, while keeping those values server-side inside encrypted playback tokens.
- Preserve existing providers, playlists, authentication, routes, relay configuration, and access rules.
- Bump the visible app version and add English “What’s new” notes.

## Technical details
- Parse standard M3U header forms such as `#EXTVLCOPT:http-referrer`, `#EXTVLCOPT:http-user-agent`, Kodi properties, and pipe-suffixed URL headers.
- Extend opaque stream tokens to carry a small allowlisted header set; propagate it to HLS manifests and segments without exposing provider URLs or credentials.
- Remove the full-list `MutationObserver` clone loop, cap each overlay render, and clean up stale media listeners/timers when switching channels.
- Verify player controls, repeated menu/settings use, fullscreen entry/exit, header forwarding, mobile rendering, and project health.
