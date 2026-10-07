# AirPalm v0.4

New file: GestureEngine.java (all gesture logic, pure Java, tested off-device with test/run.sh)

Fixes
- Finger "up" detection is rotation-proof (wrist distance, not screen-up), so tilted hands work.
- Ring/pinky tolerance + debounce/hysteresis: single bad frames no longer cancel scrolling.
- Pinch thresholds are relative to hand size, with separate press/release levels (no flicker).
- Tap uses the cursor position from ~150 ms before the pinch, so it hits what you pointed at.
- Cursor frozen while pinching; One-Euro filter (smooth when slow, fast when moving).
- Hand area of the camera maps to the whole screen (edges reachable).
- Scroll is continuous "joystick": hold two fingers, move hand up/down, farther = faster. Dead zone + slow-drift follow.
- New: pinch-hold + move up/down also scrolls. New: thumb+middle pinch = Back.
- ~22 fps with hand, ~5 fps when idle. Camera preview optional. Sliders for smoothness / pinch / scroll speed.
- Cursor colour = state (green move, yellow armed, red pressed, blue scrolling).

Test on a PC (JDK 11+): ./test/run.sh
