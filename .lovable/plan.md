# Compact mobile player controls

## What will change
- Remove the visible loading spinner from both Live TV and IPTV players while keeping automatic buffering recovery active in the background.
- Keep the player fixed in place and tune recovery so temporary stalls are handled without needless reloads or visible interruptions.
- Move the active provider/playlist selector into the top bar beside Account on mobile, styled as a compact server selector.
- Replace the always-visible category dropdown with a Categories control that opens a smooth side drawer containing the available categories and channel counts.
- Apply the same interaction pattern to Live TV and IPTV without changing provider data, channel data, or access rules.

## Technical details
- Reuse the existing provider and category state and APIs; only their presentation and event wiring change.
- Keep desktop controls usable while optimizing the 384–430px Android layout.
- Preserve hls.js/mpegts.js fallback, codec repair, and self-healing behavior; avoid showing recovery spinners over playback.
- Verify the mobile layout, category switching, player continuity behavior, console output, and project health.
