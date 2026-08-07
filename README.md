# Pulse Tones — Android

An isochronic tone generator (pitch, beat rate, and volume controls) packaged as a native Android app. The audio engine is the same Web Audio implementation from the HTML version, running inside a WebView with sample-accurate pulse scheduling.

## Build it (Android Studio — easiest)

1. Install [Android Studio](https://developer.android.com/studio) if you don't have it.
2. **File → Open** and select this `PulseTones` folder. Let Gradle sync (first sync downloads dependencies; takes a few minutes).
3. Plug in your phone with USB debugging enabled (Settings → Developer options), or start an emulator.
4. Press the green **Run** button. Done.

To produce a shareable APK instead: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. The APK lands in `app/build/outputs/apk/debug/app-debug.apk` — copy it to any phone and install it (you'll need to allow "install from unknown sources").

## Build it (command line)

With Android SDK installed and `ANDROID_HOME` set:

```
./gradlew assembleDebug        # Mac/Linux
gradlew.bat assembleDebug      # Windows
```

## Notes

- **Minimum Android version:** 8.0 (API 26).
- **Background playback:** audio is synthesized natively (Kotlin `AudioTrack`) inside a foreground media service, so it keeps playing with the screen off or the app backgrounded. A persistent notification with a **Stop** button appears while tones play.
- **Audio focus is respected:** playback stops if another app takes over audio (e.g. a phone call or another media app).
- On Android 13+, the app asks for notification permission on first launch — this only affects whether the playback notification is visible; audio works either way.
- The UI lives at `app/src/main/assets/index.html`. It auto-detects the native bridge: inside the app it drives the Kotlin synth; opened in a plain browser it falls back to WebAudio. Same file works both places.
