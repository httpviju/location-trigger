# Location Trigger

Minimal, ad-free, **100% offline** Android SMS location responder (`com.locationtrigger.clean`).

Set a secret trigger PIN and turn **Trigger Active** ON. When any phone sends an SMS containing `LOC <PIN>` (or just `<PIN>`), this app fetches the phone's current **High-Accuracy GPS** location offline and replies via SMS with a clickable Google Maps coordinate link, battery percentage, and timestamp.

## Key Features

- **100% Offline (`No INTERNET Permission`)**: Zero internet, zero cloud servers, zero analytics, and zero ads. Incoming SMS triggers and outgoing replies work purely over cellular SMS and device GPS/network providers.
- **High-Accuracy GPS**: Uses `LocationRequest.QUALITY_HIGH_ACCURACY` on Android 12+ (`API 31+`) across `GPS`, `Fused`, and `Network` providers.
- **Background & Closed-App Execution**: Uses manifest-registered [`SmsTriggerReceiver`](app/src/main/java/com/locationtrigger/clean/SmsTriggerReceiver.kt), a persistent [`LocationTriggerService`](app/src/main/java/com/locationtrigger/clean/LocationTriggerService.kt) (`location|remoteMessaging`, `stopWithTask="false"`), [`BootReceiver`](app/src/main/java/com/locationtrigger/clean/SmsTriggerReceiver.kt#L55), `"Allow all the time"` background location (`ACCESS_BACKGROUND_LOCATION`), and battery optimization exemption.
- **Live GPS Status Card**: Shows live Phone Location (GPS) `ON / OFF` status (auto-refreshes on resume) with a 1-tap shortcut to Android Location Settings. If triggered while system GPS is turned OFF, replies clearly with `"Phone Location/GPS is turned OFF."`
- **Shortcode / OTP Guard**: Ignores promotional alphanumeric sender IDs (requires sender numbers with at least 5 digits).

## Android OS Note on GPS Toggle

Standard third-party Android apps cannot silently toggle the system-wide Location/GPS switch ON or OFF (`WRITE_SECURE_SETTINGS` is restricted to system/ADB/root). Because of this OS security model, grant **Allow all the time** (`ACCESS_BACKGROUND_LOCATION`) and keep phone Location **ON** (or use the in-app **Turn ON / Settings** shortcut).

## Permissions Used

| Permission | Purpose |
| :--- | :--- |
| `RECEIVE_SMS`, `READ_SMS`, `SEND_SMS`, `READ_PHONE_STATE` | Detect trigger SMS (`LOC <PIN>` / `<PIN>`) and send the location SMS reply on the matching SIM. |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` | Fetch High-Accuracy GPS coordinates even when the app is closed or in the background. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_REMOTE_MESSAGING`, `POST_NOTIFICATIONS` | Keep the background trigger service active and visible per Android requirements. |
| `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Auto-resume after reboot and prevent Doze mode from blocking SMS trigger replies. |

*Explicitly absent:* `android.permission.INTERNET`.

## Build from Source

Requires JDK 21 and Android SDK (`compileSdk = 36`, `minSdk = 26`).

```bash
git clone https://github.com/httpviju/location-trigger.git
cd location-trigger
./gradlew testDebugUnitTest assembleRelease
```

To sign release builds (`app/build/outputs/apk/release/app-release.apk`), create a local `keystore.properties` (gitignored) at the repo root alongside `app/release.jks`:

```properties
storeFile=release.jks
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

---

<sub>Built by **Vijuu** ([@httpviju](https://github.com/httpviju))</sub>
