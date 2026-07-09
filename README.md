# SmsSync — SMS to Telegram Forwarder

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-8.0%2B-brightgreen)](https://developer.android.com/about/versions/oreo)
[![Release](https://img.shields.io/github/v/release/AndreyDochkin/smssync)](https://github.com/AndreyDochkin/smssync/releases)

Lightweight Android app that forwards incoming SMS to a Telegram chat in real-time. No servers, no cloud.

---

## Quick Start

1. **Create a bot** — Chat [@BotFather](https://t.me/BotFather), run `/newbot`, save the token.
2. **Get Chat ID** — Message your bot, then visit `https://api.telegram.org/botYOUR_TOKEN/getUpdates` and grab the `chat.id`.
3. **Install APK** — Download from [Releases](https://github.com/AndreyDochkin/smssync/releases), enable "Install from unknown apps", open the APK.
4. **Configure** — Enter bot token + chat ID in the app and tap **Start Service**.

## Build from Source

```bash
git clone https://github.com/AndreyDochkin/smssync.git
cd smssync/SmsToTelegram
./gradlew assembleRelease
```

APK at `app/build/outputs/apk/release/app-release-unsigned.apk`.

## Tech Stack

Kotlin 1.9, AGP 8.2, Gradle 8.5, OkHttp 4.12, Min SDK 26, Target SDK 34.

## License

MIT — see [LICENSE](LICENSE).