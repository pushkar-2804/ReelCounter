# Reel Counter

Android app that counts how many **Instagram Reels** and **YouTube Shorts** you scroll through.

- Shows a small live counter in the top-left while Reels/Shorts is open:
  "This session: 12 · Reels today: 87 · All: 140". It hides when you leave the player or switch apps.
- Logs every scroll **by day and by hour**, per app, in a local SQLite database.
- Main screen: today's total, a stacked hourly bar chart, an hour-by-hour list, a day-by-day
  history (tap a day, or use ◀ ▶), CSV export and a button to clear all data.
- No internet permission: all data stays on the phone.

## Install

1. Get the APK:
   - **GitHub Actions:** every push builds it; download `ReelCounter-apk` from the run's page.
   - **Android Studio:** open this folder and press Run, or run `./gradlew installDebug`.
2. Open the app, tap **Enable counting** and switch on *Reel Counter* in Accessibility settings.
3. On Android 13+, if the switch is greyed out: Settings → Apps → Reel Counter → ⋮ →
   **Allow restricted settings**, then try again.
4. Open Instagram Reels or YouTube Shorts and start scrolling — the counter appears.

Some phones (Xiaomi, Oppo, Vivo, Samsung with battery saving) stop background apps. If counting
stops, set the app's battery usage to **Unrestricted**.

For development you can enable the service without tapping through Settings:

```bash
adb shell settings put secure enabled_accessibility_services com.reelcounter/com.reelcounter.ReelCounterService
adb shell settings put secure accessibility_enabled 1
```

## How it works

Everything is in `ReelCounterService.kt`, an `AccessibilityService`:

- **Reel page detection:** it looks for view IDs that exist only in the full-screen player
  (`clips_viewer_view_pager` on Instagram, `reel_recycler` / `reel_player_page_container` on
  YouTube). When one is visible it shows the overlay; when it disappears or another app comes
  to the front, it hides it.
- **Counting:**
  - *Instagram:* on scroll events from that pager, it counts one reel each time the pager settles
    on a *different* item (so a half-swipe that springs back is not counted).
  - *YouTube:* Shorts doesn't send a scroll event for every swipe, so instead it counts each time
    a different `reel_player_page_container` fills the screen, re-checking shortly after the app
    goes quiet. A swipe that springs back leaves the same page in place and isn't counted.
- **Storage:** `CountStore` keeps one row per (app, day, hour) with a running count.

If Instagram or YouTube rename their views in an update and detection stops working, update the
`PAGE_IDS` / `PAGER_IDS` / `ITEM_IDS` lists at the bottom of `ReelCounterService.kt`. You can find
the current IDs with Android Studio's *Layout Inspector*, or with
`adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml` while the player is open.

## License

[MIT](LICENSE): free to use, modify and share, including commercially, as long as the copyright notice is kept.
