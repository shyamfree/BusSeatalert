# Bus Seat Alert

Android app that watches seat availability on government bus sites — Kerala RTC
(onlineksrtcswift.com), Karnataka RTC (ksrtc.in) and TNSTC (tnstc.in) — and alerts you by
push notification, SMS or email when seats open up, a full bus gets a cancellation, or a new
bus is added.

## Features
- One search: tick the sites, pick From/To (live place suggestions per site, several stops
  per site), one or more journey dates.
- Results with filters (site, seats, departure time, bus type, max fare; default: buses with
  seats, cheapest first).
- Live seat map inside each bus card (ladies-only, booked-by-woman, differently-abled seats).
- Background checks every 15+ minutes with a master ON/OFF switch.
- One highlighted phone notification (newest replaces older); every alert kept in the
  in-app Notifications panel, which opens details with "Refresh now".
- "Book" opens the operator's site already set to the route and date (seat chart for
  Kerala/Karnataka).

## Build
Requires JDK 17+ and the Android SDK (`local.properties` with `sdk.dir=...`).

```
./gradlew assembleDebug      # debug APK: app/build/outputs/apk/debug/
./gradlew assembleRelease    # signed release APK (needs the release key, see below)
./gradlew testDebugUnitTest  # tests; add -Dlive=true to also hit the real sites
```

### Release signing
The release key is **not** in this repository. Put `bus-seat-alert-release.jks` in
`release/` and create `release/keystore.properties`:

```
storeFile=release/bus-seat-alert-release.jks
storePassword=...
keyAlias=busseatalert
keyPassword=...
```

Without it, `assembleRelease` produces an unsigned APK.

## Code layout
- `providers/` — one provider per site: search, seat maps, place names, bus types.
- `check/` — background checker (WorkManager) and change detection for alerts.
- `notify/` — push, SMS (from the phone) and Gmail email.
- `ui/` — Jetpack Compose screens (Search, Notifications, notification details, guide, settings).
