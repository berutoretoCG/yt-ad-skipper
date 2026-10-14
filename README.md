# YT Ad Skipper

Android app with an Accessibility Service that watches only the YouTube app
(`com.google.android.youtube`) and taps **Skip** on ads as soon as the button appears.

## Build & install
1. Open this folder in Android Studio (Koala or newer). Let it sync; it will add the Gradle wrapper if asked.
2. Plug in the phone with USB debugging on and press **Run** — or *Build → Build APK(s)* and sideload the APK.

## Turn it on
1. Open **YT Ad Skipper** → *Open Accessibility settings*.
2. Accessibility → *Installed / Downloaded apps* → **YT Ad Skipper** → On.
   - Android 13+: if the toggle is greyed out ("Restricted setting"), go to
     Settings → Apps → YT Ad Skipper → ⋮ → *Allow restricted settings*, then try again.
3. Use YouTube normally. The main screen shows how many ads were skipped.

## How it works
`SkipAdService.kt`:
1. Looks for known skip-button view IDs (`skip_ad_button`, `modern_skip_ad_text`, …).
2. Falls back to text/description matches ("Skip", "Saltar", "Omitir", …).
3. Clicks the node (or its clickable parent); if that fails, dispatches a real tap at its centre.
A 1.5 s cooldown prevents double taps.

## If it stops working
YouTube changes its view IDs every so often. To find the new one:
- Enable *Developer options → Show layout bounds*, or run
  `adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml`
  while the Skip button is on screen, and look for its `resource-id` or `text`.
- Add it to `SKIP_VIEW_IDS` or `SKIP_LABELS` in `SkipAdService.kt`.

## Limits
- It only skips *skippable* ads, the same as you tapping Skip. Unskippable ads still play.
- Some battery optimisers (Xiaomi, Huawei, Samsung) kill accessibility services; exclude the app from battery optimisation if it turns itself off.
