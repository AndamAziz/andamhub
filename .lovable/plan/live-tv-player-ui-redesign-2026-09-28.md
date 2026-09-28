# Live TV player UI redesign

## What will change
- Rebuild the Live TV header, tabs, sticky 16:9 player, now-playing bar, group chips, search, and compact channel rows as one mobile-first interface.
- Replace the current player controls with a YouTube-style overlay, prev/play/next actions, live-aware timeline, and a single settings sheet.
- Move channel browsing out of the video and into the page list plus a reusable 75%-height bottom sheet.
- Make fullscreen viewport-safe without orientation locking, and add a two-column non-fullscreen landscape layout.
- Apply the same visual language to Movies and Series while preserving all existing data and playback behavior.

## Technical details
- Limit changes to presentation markup, CSS, accessibility attributes, and UI event wiring in `public/andam.html`.
- Keep stream, relay, authentication, provider, playlist, and route logic unchanged.
- Use 100dvh/100dvw, safe-area insets, 44px targets, focus-visible states, 12px cards, pill chips, and reduced-motion support.
- Update project notes to record the UI-only boundary.
