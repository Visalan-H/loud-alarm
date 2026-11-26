# Loud Alarm

An Android alarm clock that won't shut up until you solve three addition problems. Snooze and turn-off both stay locked until you finish.

For real emergencies, holding the stop bar for 5 seconds turns it off without the math. That costs you your streak, the count of mornings you got up without snoozing. Loudness starts at 50% and goes from 10% to 100%.

It runs entirely on the phone. There's no server, no account, and no internet permission. The only code in the APK is this app plus the Kotlin standard library.

## How it stays reliable

- `AlarmManager.setAlarmClock()` schedules every alarm. Android treats this API as a real wake-up alarm, so it fires on the minute and ignores Doze.
- The tone is synthesized in code and played on the alarm stream. No sound file can go missing, and silent mode doesn't mute it.
- While the alarm rings, a foreground service resets the alarm volume to your chosen level every second. The volume keys do nothing on the ring screen.
- Alarms come back after a reboot, even before you unlock the phone. If the phone restarts mid-alarm, the alarm rings again a minute later.
- Opening the app re-registers every alarm. That repairs the damage after a force stop.
- A ring stops on its own after an hour.

## Build

You need JDK 17 or newer and the Android SDK with platform 35.

```powershell
.\gradlew.bat assembleRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`. It's signed with your local debug key, so updates install over each other only from the same machine.

## First run

The app lists any setting that could stop an alarm and gives you a Fix button for each one. Clear all of them. On Xiaomi, Samsung, Oppo and similar phones, also allow the app to autostart. Then use **Test: ring in 10 seconds** with the screen locked.

## Limits

- Force stop from system settings wipes scheduled alarms until you open the app again. Android doesn't let any app get around this.
- A dead battery or a powered-off phone can't ring.
