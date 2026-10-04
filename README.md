# PowerPing

Charge-threshold alarm for Android. Plug in, and PowerPing rings an alarm the
moment your battery reaches the level you set — so you can unplug in time and
protect battery health. No more overnight overcharging.

## Features

- **Charge target alarm** — pick a threshold between 50% and 95%; a looping
  alarm (following the system *alarm volume* stream) reminds you to unplug.
- **One alert per charge** — smart debounce: the alarm fires once per charging
  session; unplugging resets it for the next session.
- **Zero-drain monitoring** — event-driven battery monitoring via sticky
  broadcasts. No polling, no background CPU.
- **Auto-resume** — monitoring survives reboots and automatically restarts
  when a charger is plugged in.
- **Lightweight UI** — a single screen with live status, threshold slider and
  an alarm preview button.
- **English & 简体中文** — fully localized.

## How it works

A persistent foreground service (`specialUse`) listens to sticky battery
broadcasts. When the battery level reaches your target during a charging
session, it plays a looping alarm and posts a high-priority notification with
a "Stop" action. Unplugging the charger ends the session and re-arms the
monitor.

## Requirements

- Android 7.0 (API 24) or newer
- On aggressive ROMs (MIUI/HyperOS, EMUI, ColorOS), allow auto-start and
  background running for reliable monitoring.

## Building

```bash
./gradlew assembleDebug    # debug APK
./gradlew assembleRelease  # unsigned release APK
```

Requires JDK 17+ and an Android SDK.

## License

[GPL-3.0](LICENSE) — PowerPing is free software: you can redistribute it
and/or modify it under the terms of the GNU General Public License as
published by the Free Software Foundation, either version 3 of the License,
or (at your option) any later version.
