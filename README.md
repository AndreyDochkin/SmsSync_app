# SmsSync — SMS to Telegram Forwarder

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-8.0%2B-brightgreen)](https://developer.android.com/about/versions/oreo)
[![Release](https://img.shields.io/github/v/release/AndreyDochkin/SmsSync_app)](https://github.com/AndreyDochkin/SmsSync_app/releases)

Lightweight Android app that forwards incoming SMS to a Telegram chat in real-time. No servers, no cloud.

## Features

- **Real-time forwarding** — Incoming SMS are sent to Telegram instantly via a foreground service
- **Multi-SIM support** — Auto-detects which SIM received the message (carrier name or custom label)
- **Sender blacklist** — Block specific numbers or use wildcards (e.g. `123*`) to block prefixes
- **Customizable message template** — Use placeholders: `{sender}`, `{message}`, `{sim}`, `{battery}`, `{time}`, `{date}`
- **Encrypted credentials** — Bot token and chat ID stored securely via `EncryptedSharedPreferences`
- **Auto-retry** — Failed sends are queued and retried with exponential backoff
- **Daily statistics** — Tracks forwarded message count per day
- **Test mode** — Emulate an incoming SMS from within the app to verify forwarding works
- **Battery optimization** — Prompts to disable battery optimization for reliable delivery
- **Custom SIM naming** — Give each SIM slot a friendly name (e.g. "Personal", "Business")

## Architecture

```
SmsReceiver (BroadcastReceiver)
  → detectSimSlot() — multi-layered SIM detection (intent extras → ContentProvider)
  → TelegramSender.sendSmsToTelegram() — format template + POST to Telegram Bot API
  → MessageRetryQueue — persist failed messages for retry via WorkManager

ForwardingManager — central state: pause/resume, blacklist, SIM info, daily counters
```

## Quick Start

1. **Create a bot** — Chat [@BotFather](https://t.me/BotFather), run `/newbot`, save the token.
2. **Get Chat ID** — Message your bot, then visit `https://api.telegram.org/botYOUR_TOKEN/getUpdates` and grab the `chat.id`.
3. **Install APK** — Download from [Releases](https://github.com/AndreyDochkin/SmsSync_app/releases), enable "Install from unknown apps", open the APK.
4. **Configure** — Enter bot token + chat ID in the app and tap **Start Service**.

## Build from Source

```bash
git clone https://github.com/AndreyDochkin/smssync.git
cd smssync/SmsToTelegram
./gradlew assembleRelease
```

APK at `app/build/outputs/apk/release/app-release-unsigned.apk`.

## Permissions

| Permission | Purpose |
|---|---|
| `RECEIVE_SMS` | Detect incoming SMS messages |
| `READ_SMS` | Read SIM slot from SMS database (fallback detection) |
| `POST_NOTIFICATIONS` | Foreground service notification (Android 13+) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Keep service alive in Doze mode |

## Tech Stack

Kotlin 1.9, AGP 8.2, Gradle 8.5, OkHttp 4.12, Min SDK 26, Target SDK 34.

## License

MIT — see [LICENSE](LICENSE).