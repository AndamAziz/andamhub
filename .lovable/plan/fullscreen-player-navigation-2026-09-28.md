# Fullscreen player navigation

## What will change
- Remove the fullscreen icon beside the LIVE badge from both Live TV and IPTV player bars; keep category controls outside the player.
- Let users enter fullscreen through the video’s standard fullscreen control and open a clean navigation overlay while fullscreen.
- Show categories/groups in a translucent left sidebar and numbered channels with logos in a scrollable right panel.
- Allow category and channel switching without leaving fullscreen; highlight the active category and currently playing channel.
- Keep controls touch-friendly on phones and D-pad/keyboard accessible on TV devices, with automatic hiding while watching.

## Playback continuity
- Reuse the existing player instance when navigating the fullscreen menus.
- Preserve the current channel while opening, closing, or changing categories.
- Reset recovery attempts after healthy playback and avoid overlapping restart loops.
- Retain HLS/MPEG-TS auto-detection, codec repair, relay fallback, and invisible self-healing.

## Verification
- Check Live TV and IPTV fullscreen layouts on mobile and desktop.
- Test keyboard/D-pad navigation, category selection, channel switching, and overlay auto-hide.
- Confirm no player overlay icons remain beside LIVE and no visible loading spinner appears.
- Confirm the preview builds without errors.
