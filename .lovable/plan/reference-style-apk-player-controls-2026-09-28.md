# Reference-style APK player controls

## Player controls
- Replace the browser’s default controls with a clean custom bottom bar over the video.
- Include play/pause, 10-second rewind/forward, elapsed and duration time, mute/volume, fit mode, settings, menu toggle, and fullscreen.
- Keep the bar hidden while watching and reveal it on tap, pointer movement, or remote/keyboard input.

## Fullscreen channel menu
- Use the menu button to open or close an immersive overlay without pausing the video.
- Show categories/groups on the left and channels on the right with numbers, logos, names, and active states.
- Keep category filtering and channel switching inside fullscreen, with touch, keyboard, and D-pad support.

## Playback continuity
- Keep the current media engine alive when menus and controls open or close.
- Prevent overlapping recovery restarts and retain the existing relay, codec repair, HLS, and MPEG-TS fallbacks.
- Avoid visible loading spinners and buffering overlays.

## Verification
- Test the control bar, overlay toggle, category/channel switching, fit modes, and fullscreen on phone and desktop sizes.
- Confirm the page builds without errors and the previous LIVE-row overlay icons stay removed.
