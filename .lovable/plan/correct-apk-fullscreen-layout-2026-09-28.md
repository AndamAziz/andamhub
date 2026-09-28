# Correct APK fullscreen layout

## Changes
- Make APK fullscreen show only the video player, never the site header, tabs, or page content.
- Lock the fullscreen layer to the rotated Android viewport using fixed physical screen dimensions and safe-area handling.
- Hide Android system bars during playback where supported, then restore them and portrait orientation on exit.
- Keep the same video element and playback engine alive throughout rotation so audio and video do not restart.
- Keep the bottom controls and channel/category menu correctly positioned in landscape.

## Verification
- Simulate the APK fullscreen path at phone and landscape sizes.
- Confirm the player fills the screen, page chrome is absent, controls fit, and exit restores the normal layout.
- Confirm the project builds without errors.
