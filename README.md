# Location Trigger

Minimal, ad-free, **100% offline** Android SMS Location Trigger, Scheduled Auto Location Sender, and SMS Auto-Forwarder (`com.locationtrigger.clean`).

## Key Features

1. **SMS Location Trigger (`LOC <PIN>` / `<PIN>`)**: Replies automatically to trigger SMS messages with **High-Accuracy GPS** coordinates (`https://maps.google.com/?q=lat,lon`), accuracy, battery %, and time.
2. **Scheduled Auto Location Sender**: Automatically sends current High-Accuracy GPS SMS to your saved destination phone number every **5m / 15m / 30m / 60m** — even with no incoming trigger SMS.
3. **SMS Auto-Forwarder**: Forwards incoming SMS messages to your saved destination number automatically (skips PIN triggers and includes a self-loop guard).
4. **100% Offline (`No INTERNET Permission`)**: Works purely over cellular SMS and device GPS/network providers with zero ads or trackers.
5. **Background & Reboot Resilient**: Uses synchronous `onReceive()` dispatch in [`SmsTriggerReceiver`](app/src/main/java/com/locationtrigger/clean/SmsTriggerReceiver.kt), persistent [`LocationTriggerService`](app/src/main/java/com/locationtrigger/clean/LocationTriggerService.kt) (`location|remoteMessaging`, `stopWithTask="false"`), [`AutoSendAlarmReceiver`](app/src/main/java/com/locationtrigger/clean/AutoSendAlarmReceiver.kt), Expedited [`TriggerJobService`](app/src/main/java/com/locationtrigger/clean/TriggerJobService.kt) fallback, and [`BootReceiver`](app/src/main/java/com/locationtrigger/clean/SmsTriggerReceiver.kt#L86).
6. **Live GPS Status Card**: Shows live Phone Location (GPS) `ON / OFF` status (auto-refreshes on resume) with a 1-tap shortcut to Android Location Settings.

## Android OS Note on GPS Toggle & Background Permissions

Standard third-party Android apps cannot silently toggle the system-wide Location/GPS switch ON or OFF (`WRITE_SECURE_SETTINGS` is restricted to system/ADB/root). Grant **Allow all the time** (`ACCESS_BACKGROUND_LOCATION`) and disable battery restrictions (plus **Autostart** on Xiaomi/Vivo/Oppo/Realme) so background triggers & scheduled sends run reliably when closed.

## Permissions Used

| Permission | Purpose |
| :--- | :--- |
| `RECEIVE_SMS`, `READ_SMS`, `SEND_SMS`, `READ_PHONE_STATE` | Read incoming SMS triggers/messages and send replies or forwards over cellular SIM. |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` | Fetch High-Accuracy GPS coordinates in foreground and background. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_REMOTE_MESSAGING`, `POST_NOTIFICATIONS` | Keep background listener active and post local alerts. |
| `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Re-arm schedules/service after reboot and prevent Doze delays. |

*Explicitly absent:* `android.permission.INTERNET`.

## Build from Source

Requires JDK 21 and Android SDK (`compileSdk = 36`, `minSdk = 26`).

```bash
git clone https://github.com/httpviju/location-trigger.git
cd location-trigger
./gradlew testDebugUnitTest assembleRelease
```

To sign release builds (`app/build/outputs/apk/release/app-release.apk`), add a local `keystore.properties` (gitignored) at the repo root alongside `app/release.jks`:

```properties
storeFile=release.jks
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

---

<sub>Built by **Vijuu** ([@httpviju](https://github.com/httpviju))</sub>
