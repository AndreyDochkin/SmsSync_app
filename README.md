# SmsSync — SMS to Telegram Forwarder

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-8.0%2B-brightgreen)](https://developer.android.com/about/versions/oreo)
[![Release](https://img.shields.io/github/v/release/AndreyDochkin/SmsSync_app)](https://github.com/AndreyDochkin/SmsSync_app/releases)
[![Build](https://github.com/AndreyDochkin/SmsSync_app/actions/workflows/build-release.yml/badge.svg)](https://github.com/AndreyDochkin/SmsSync_app/actions/workflows/build-release.yml)

Lightweight Android app that forwards incoming SMS to a Telegram chat in real-time. No servers, no cloud, no third-party dependencies for the forwarding logic.

## Features

- **Real-time forwarding** — Incoming SMS are sent to Telegram instantly via a foreground service
- **Multi-SIM support** — Auto-detects which SIM received the message using multi-layered detection (intent extras → ContentProvider)
- **SIM carrier & custom naming** — Shows operator name (e.g. "Vodafone") or user-defined labels (e.g. "Personal", "Business")
- **Customizable message template** — Use placeholders: `{sender}`, `{message}`, `{sim}`, `{battery}`, `{time}`, `{date}`
- **Battery level in messages** — `{battery}` placeholder shows current charge (e.g. `85%` or `Charging 92%`)
- **Encrypted credentials** — Bot token and chat ID stored securely via `EncryptedSharedPreferences`
- **Auto-retry with backoff** — Failed sends are queued persistently and retried with exponential backoff
- **Daily statistics** — Tracks forwarded message count per day
- **Test mode** — Emulate an incoming SMS from within the app to verify forwarding works
- **Battery-aware keep-alive** — WorkManager-based keep-alive every 25 minutes (respects Doze mode, no wake locks)
- **Boot persistence** — Automatically restarts after device reboot

## Architecture

```
SmsReceiver (BroadcastReceiver)
  → detectSimSlot() — multi-layered SIM detection (intent extras → ContentProvider)
  → TelegramSender.sendSmsToTelegram() — format template + POST to Telegram Bot API
  → MessageRetryQueue — persist failed messages for retry via WorkManager

ForwardingManager — central state: pause/resume, SIM info, daily counters, battery level

SmsForwarderService (Foreground Service)
  → KeepAliveWorker (WorkManager, every 25 min) — ensures service stays alive on aggressive OEMs
```

## Quick Start

1. **Create a bot** — Chat [@BotFather](https://t.me/BotFather), run `/newbot`, save the token.
2. **Get Chat ID** — Message your bot, then visit `https://api.telegram.org/botYOUR_TOKEN/getUpdates` and grab the `chat.id`.
3. **Install APK** — Download the latest APK from [Releases](https://github.com/AndreyDochkin/SmsSync_app/releases), enable "Install from unknown apps", open the APK.
4. **Configure** — Open the app, tap the settings icon (⚙), enter bot token + chat ID, and save. The service starts automatically when SMS permission is granted.

### Message Template

Default template:
```
📩 <b>New SMS</b>
━━━━━━━━━━━━━━
👤 {sender}
💬 {message}
📱 {sim}
🔋 {battery}
🕐 {time}
```

Available placeholders: `{sender}` `{message}` `{sim}` `{battery}` `{time}` `{date}`

## Build from Source

```bash
git clone https://github.com/AndreyDochkin/SmsSync_app.git
cd SmsSync_app
./gradlew assembleDebug
```

The APK is automatically copied to the project root as `SmsSync-v{version}.apk`.

## Permissions

| Permission | Purpose |
|---|---|
| `RECEIVE_SMS` | Detect incoming SMS messages |
| `READ_SMS` | Read SIM slot from SMS database (fallback detection) |
| `POST_NOTIFICATIONS` | Foreground service notification (Android 13+) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Keep service alive in Doze mode |

## Battery Efficiency

SmsSync is designed to minimize battery impact:
- **Foreground service** uses `IMPORTANCE_LOW` notification (minimal system overhead)
- **Keep-alive** uses WorkManager (25 min interval) which batches with other system jobs and respects Doze mode
- **No AlarmManager** — removed in v2.3.2 to eliminate unnecessary wake-ups every 5 minutes
- **No wake locks** during keep-alive pings
- **Foreground service only** — the app does not run a background process that polls or checks continuously

When the device is in Doze mode, SMS reception still works because Android delivers SMS broadcasts as high-priority messages that bypass Doze restrictions.

## Tech Stack

Kotlin 1.9, AGP 8.2, Gradle 8.5, OkHttp 4.12, WorkManager 2.9, Min SDK 26, Target SDK 34.

## License

MIT — see [LICENSE](LICENSE).