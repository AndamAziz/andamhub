# Reliable fullscreen playback

## Changes
- Replace the fragile fullscreen sequence with one user-gesture-safe flow for laptop browsers and the Android app.
- Use native fullscreen on laptops; use a stable app fullscreen layer plus Android landscape lock in the APK so rotation cannot cancel fullscreen.
- Preserve the active video element and playback engine during entry, exit, resizing, and rotation; never reload or pause the stream just because fullscreen changed.
- Prevent duplicate fullscreen transitions and cleanly restore portrait/layout when users exit or press Back.
- Keep Fit, Fill, Wide, playback, audio, settings, and category/channel controls usable in both fullscreen modes.

## Validation
- Test enter/exit repeatedly on laptop-sized and mobile-sized screens.
- Confirm the video element keeps the same source, time, and playing state across fullscreen transitions.
- Confirm controls remain clickable, sizing stays full-screen, and no runtime errors occur.
- Confirm the project builds successfully.

## Technical details
- Changes stay inside the existing player/layout code in `public/andam.html`; the installed Android orientation plugin remains unchanged.
- Physical Android landscape rotation will still need final confirmation in a newly generated APK.
