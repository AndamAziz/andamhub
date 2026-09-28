# Fullscreen rotation and compact player layout

## Changes
- Make the fullscreen action request landscape orientation through the Android app bridge before entering fullscreen, with browser fallback and portrait restoration on exit.
- Keep orientation state synchronized for native fullscreen, fallback fullscreen, Android back navigation, and fullscreen failures.
- Remove the extra top spacing in Live TV and IPTV views so the player/section controls sit directly beneath the fixed header without overlap.
- Recalculate sticky player/list offsets for mobile and desktop using the actual compact header height.

## Validation
- Check mobile and laptop layouts for header overlap or blank gaps.
- Verify fullscreen entry/exit and fallback behavior in-browser; confirm the Android configuration still includes the orientation plugin during APK builds.
- Confirm the project builds without errors.

## Technical details
- Changes stay limited to `public/andam.html` player/layout code and the existing Android wrapper configuration if required.
- Physical device rotation must be finally confirmed on a newly built APK because desktop browser emulation cannot rotate Android hardware.
