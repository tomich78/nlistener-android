# NListener — Android app

Native Android client for [NListener](https://www.nlistener.com.ar), a SaaS that lets businesses watch incoming bank transfers in real time without giving employees access to the bank account.

This app runs on the owner's phone, captures payment notifications from banking/wallet apps via `NotificationListenerService`, filters and parses them, and syncs them to the web dashboard.

🔗 **Web app + API:** [github.com/tomich78/notification-listener](https://github.com/tomich78/notification-listener)
🔗 **Live:** [www.nlistener.com.ar](https://www.nlistener.com.ar)

<!-- Agregá acá 1-2 screenshots de la app: pantalla principal y el escáner QR. -->

## What it does

1. **Pairing** — the user scans a QR from the web dashboard (encodes server URL + device token). No login inside the app.
2. **Capture** — `NotificationListenerService` receives notifications from the apps the user selected (Mercado Pago, Naranja X, Ualá, Brubank, Santander, BBVA, Galicia, Macro, ICBC, Personal Pay…).
3. **Filter & queue** — non-payment notifications are discarded; the rest are written to a persistent on-disk queue *before* any network call.
4. **Sync** — the payment is POSTed to the API. On failure, WorkManager retries with exponential backoff. On success, the notification is dismissed from the tray so it isn't re-captured.

## Stack

- **Kotlin** · **Jetpack Compose** (Material 3)
- **`NotificationListenerService`** — notification capture
- **WorkManager** — retry with exponential backoff for failed sends
- **Coroutines** — async, with `SupervisorJob` scoping per service
- **OkHttp** — HTTP client
- **CameraX + ML Kit Barcode Scanning** — QR pairing
- **Firebase Cloud Messaging** — remote reconnect push
- **Firebase Crashlytics** — crash reporting (essential for OEM-specific bugs I can't reproduce locally)
- **SharedPreferences** — config + persistent queue

## Architecture

```
app/src/main/java/com/tomich/notificationlistener/
├── MainActivity.kt          # entry point, Compose host
├── data/
│   ├── ApiClient.kt         # OkHttp calls + dedupe ID derivation
│   ├── AppConstants.kt      # supported banking/wallet packages
│   └── PrefsManager.kt      # config + persistent notification queue
├── service/
│   ├── NLService.kt         # NotificationListenerService — the core
│   ├── KeepAliveService.kt  # foreground service keeping the listener alive
│   ├── KeepAliveWorker.kt   # periodic watchdog
│   ├── NotificationSendWorker.kt  # WorkManager retry of the queue
│   ├── BootReceiver.kt      # restart after reboot
│   └── FcmService.kt        # remote reconnect via push
└── ui/
    ├── SplashScreen.kt
    ├── SetupScreen.kt       # QR pairing + manual fallback
    ├── QrScannerView.kt     # CameraX + ML Kit
    └── HomeScreen.kt        # status, app toggles, share public link
```

## Engineering notes

The interesting problems in this app were all about **surviving Android's background restrictions**, which vary wildly across manufacturers.

**Never losing a payment.**
A captured notification is persisted to disk *before* the network call, so it survives process death. Each queue entry has its own ID, so a partial failure removes only the entries that actually made it — the rest stay queued for WorkManager. On reconnect, the service also re-scans notifications still present in the tray: since successfully-sent ones are dismissed, whatever remains is exactly what failed. A `PARTIAL_WAKE_LOCK` is held around the send so the device doesn't sleep mid-request.

**Idempotent sends.**
Some devices recorded the same transfer several times. The causes were all resends: the write succeeded but the HTTP response was lost and WorkManager retried, or a notification wasn't dismissed and got re-captured, or the OEM re-posted it. Rather than trying to prevent every resend, ingestion was made idempotent — the client derives a deterministic ID (SHA-256 over the notification's stable tray key + text) and the server uses it as the document ID, so a resend overwrites its own row instead of creating a duplicate.

**Foreground service restrictions (Android 12+).**
`startForegroundService()` throws `ForegroundServiceStartNotAllowedException` when called from a non-exempt background context — this crashed the app on some devices via a WorkManager/connectivity path. Every start site is now guarded, with Crashlytics confirming the fix on real hardware.

**OEM battery management.**
Stock Android keeps the listener alive; MIUI, One UI, ColorOS and EMUI do not. The app detects the manufacturer and conditionally surfaces the exact steps needed (Xiaomi AutoStart, Samsung "unrestricted", Oppo/Realme/OnePlus, Huawei), deep-linking into the right settings screen with fallbacks when the vendor activity isn't available.

## Building

Requires two files that aren't in the repo:

- **`app/google-services.json`** — download from the Firebase console (Project settings → your Android app).
- **`keystore.properties`** in the project root, only for release builds:

  ```properties
  storeFile=/path/to/your-release-key.jks
  storePassword=...
  keyAlias=...
  keyPassword=...
  ```

Then:

```bash
./gradlew assembleDebug    # debug APK
./gradlew bundleRelease    # signed AAB for Play Console
```

---
Built and maintained by [Tomás Degano Sal](https://github.com/tomich78).
