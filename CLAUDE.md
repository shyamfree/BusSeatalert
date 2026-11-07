# Bus Seat Alert — notes for working on this repo

Android app (Kotlin, Jetpack Compose) that watches seats on Kerala RTC, Karnataka RTC and TNSTC
and alerts the user. See README.md for features. The owner tests on their own Android phone and
gets APKs from us; they don't build locally.

## Setting up a fresh cloud workspace
The Android SDK isn't preinstalled. Install it once per session:

```
mkdir -p /opt/android-sdk/cmdline-tools && cd /opt/android-sdk/cmdline-tools
curl -sS -o clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q clt.zip && mv cmdline-tools latest && rm clt.zip
yes | latest/bin/sdkmanager --licenses >/dev/null
latest/bin/sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
echo "sdk.dir=/opt/android-sdk" > <repo>/local.properties
```

- Build with `ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug`.
- Maven Central sometimes answers HTTP 429 (rate limit) on first dependency download: just rerun.
- The environment's network allow-list must include: `onlineksrtcswift.com`, `www.onlineksrtcswift.com`,
  `ksrtc.in`, `ksrtcapi.iamgds.com`, `www.tnstc.in`. (`assets.onlineksrtcswift.com` is blocked here,
  so Kerala web pages render blank in headless Chromium; the API still works.)

## Tests
- `./gradlew testDebugUnitTest` — Robolectric UI smoke tests (Search, Notifications) and Notifier.
- `./gradlew testDebugUnitTest -Dlive=true` — also hits the real sites (`LiveSearchTest`, place
  suggestion UI test). Run this after touching anything in `providers/`.
- Headless Chromium is available for checking booking links: Playwright is installed globally for
  node (`require(\`${npm root -g}/playwright\`)`), launch with `proxy: { server: process.env.HTTPS_PROXY }`
  and `NODE_EXTRA_CA_CERTS=/root/.ccr/ca-bundle.crt`.

## Delivering to the owner
- Bump `versionCode`/`versionName` in `app/build.gradle.kts` for each release.
- Release APK: needs `release/bus-seat-alert-release.jks` + `release/keystore.properties`
  (format in README). The owner keeps the key and passwords privately and uploads them when a
  signed release is wanted. **Never commit them** — this repo is public. Debug APKs need nothing.
- The installed app is the release build; a debug APK won't install over it (different key).

## How the sites work (providers/)
- Kerala (onlineksrtcswift.com) and Karnataka (ksrtc.in) run the same "iamgds" platform →
  `IamgdsProvider`: `/api/resource/getStaticCityList`, `searchRoutesV4` (journeyDate yyyy-MM-dd),
  `seatArrangement?routeID=<RouteScheduleId>`. Seat `Gender=F` on a free seat = ladies only;
  on a booked seat = booked by a woman.
- Booking links: `/seatchart/<RouteScheduleId>/<fromId>/<fromName>/<toId>/<toName>/<dd-MM-yyyy>/undefined/oneway/false/chart`.
- TNSTC (`TnstcProvider`): Struts form posts to `/OTRSOnline/jqreq.do`; place lookup returns
  `id:code:NAME^`; results are HTML (`div.bus-list`). The seat map
  (`advanceNewBooking.do?hiddenAction=SubmitToGetOnwardSeatLayout`) only works right after a search
  in the same session (cookie jar), so always search → seat map in sequence. The search also works
  as a GET link, used for Book.
- Seat counts: the sites' list numbers are unreliable (Karnataka is often off by a few; TNSTC
  includes differently-abled seats), so the checker counts bookable seats from each matching
  bus's seat map.

## Product decisions (from the owner)
- One active search (Store.activeWatch) on the Search screen; results and filters below it.
  Sites are tick boxes; place suggestions come from each ticked site, with several stops per site.
- Default result filter: all buses, seats available only, cheapest first.
- Colours: theme red/white like Kerala RTC; sites — Kerala red, Karnataka blue, TNSTC green
  (bright, not dark). FULL badge is grey.
- Search only shows results. Background checks and notifications are only for a search whose
  alert the user set (`Watch.alertOn`, "🔔 Set alert for this search" under the results).
  Changing the search (anything in `criteria()`, dates included) turns its alert off and cancels
  the phone notification.
- Notify only on change (seats freed / increased, new bus with seats): sounding push + SMS +
  email. No change → optional silent push update "No change since last check"
  (`Watch.notifyNoChange`, default on); never SMS/email for no change. Setting an alert posts a
  silent "Alert set" status.
- Phone shows **one** notification (fixed id, newest replaces older). Every change alert
  is kept in the in-app Notifications tab (swipe / select / clear all, with undo); tapping opens
  details with "Refresh now". SMS and email: one combined message per check.
- Master ON/OFF switch (green bar) stops all background work; a manual Search still works when off.
- SMS permission: Android 13+ blocks SEND_SMS for sideloaded apps ("restricted setting");
  `SmsPermissionRow` shows the status and walks the user through App info → Allow restricted settings.
- No History tab, no activity log in the UI (Guide screen instead).
- "Best pick" card above the results (`pick/Recommender.kt`): ranks buses with seats on price,
  comfort (keyword table on bus type names), journey time and preferred seat position
  (window/aisle/lower/upper from `SeatSummary`, filled in when seat maps are read). Modes:
  Balanced / Cheapest / Most comfortable / Fastest; choices persist in Store.
- App icon is generated by `tools/icon.py` (vector XML) and previewed with `tools/icon_preview.py`.
