# Android Development Setup Guide

This guide covers the setup and development of the Pi Surveillance Android client app.

## Prerequisites

- **Android Studio** (version Flamingo or later)
- **Android SDK** API level 28 or higher
- **Java Development Kit** (JDK 11+)
- **Gradle** (8.0+)
- **Kotlin** (1.8+)

## Project Setup

### 1. Import Project into Android Studio

1. Open Android Studio
2. Select **File** > **Open**
3. Navigate to the `android_app` directory in the surveillance project
4. Click **OK** to import

### 2. Sync Gradle

1. Android Studio will prompt to sync Gradle files
2. Click **Sync Now** to download all dependencies
3. Wait for the build to complete

## Project Structure

```
android_app/
├── src/
│   ├── main/
│   │   ├── kotlin/com/example/pisurveillance/
│   │   │   ├── MainActivity.kt                 # Main activity entry point
│   │   │   ├── api/                           # Retrofit service interfaces
│   │   │   ├── models/                        # Data classes for API responses
│   │   │   ├── websocket/                     # WebSocket managers
│   │   │   ├── ui/fragments/                  # UI fragments (Video, Settings, Status)
│   │   │   ├── utils/                         # Utility classes (SSL, Preferences)
│   │   │   └── viewmodel/                     # MVVM ViewModels
│   │   ├── res/
│   │   │   ├── layout/                        # XML layout files
│   │   │   ├── menu/                          # Navigation menus
│   │   │   ├── navigation/                    # Navigation graphs
│   │   │   ├── values/                        # Resources (strings, colors, dimens)
│   │   │   └── xml/                           # Network security config
│   │   └── AndroidManifest.xml
│   └── test/                                   # Unit tests (optional)
├── build.gradle.kts                           # Module-level build config
├── proguard-rules.pro                         # ProGuard obfuscation rules
└── README.md

```

## Build Variants

### Debug Build
```bash
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### Release Build
```bash
./gradlew assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk`

## Running on Device/Emulator

### Using Android Studio
1. Connect Android device via USB (with USB debugging enabled)
2. Select the device in the device dropdown (top toolbar)
3. Click **Run** (green play button)
4. Select desired variant and click **OK**

### Using Command Line
```bash
# Install debug APK
./gradlew installDebug

# Install release APK (requires signing)
./gradlew installRelease
```

## Development Tips

### Code Generation
- **ViewBinding** is enabled - no need to manually inflate layouts
- **DataBinding** is enabled - use `<variable>` tags in XML for reactive UI
- Kotlin synthetics are **deprecated** - use ViewBinding instead

### Hot Reload
Android Studio supports **Instant Run** for faster development:
1. Make code changes
2. Click **Run** or use **Ctrl+5** (Windows/Linux) / **Cmd+5** (Mac)
3. Changes apply without full rebuild/reinstall

### Debugging
1. Add breakpoints by clicking line numbers in editor
2. Run app in debug mode: **Run** > **Debug 'app'**
3. Use **Logcat** tab to view logs
4. Use Timber logging: `Timber.d("message")`, `Timber.e(exception)`

## SSL/Certificate Setup

### Self-Signed Certificate Support

If your Raspberry Pi uses a self-signed certificate:

1. **Export the certificate** from Pi:
```bash
# On Raspberry Pi
openssl x509 -in cert.pem -outform DER -out cert.der
```

2. **Place in Android project**:
```bash
# In android_app/src/main/res/raw/
cp cert.der custom_ca.der
```

3. **Reference in Network Security Config** (`network_security_config.xml`):
```xml
<certificates src="@raw/custom_ca" />
```

## Testing

### Unit Tests
```bash
./gradlew test
```

### Instrumentation Tests
```bash
./gradlew connectedAndroidTest
```

## Troubleshooting

### Gradle Sync Issues
- **Solution**: Run `./gradlew clean` followed by sync again
- Check that all dependencies are available in configured repositories

### Build Failures
- **Solution**: Check `Build` > `Clean Project`, then rebuild
- Update Android SDK tools via SDK Manager

### Runtime Crashes
- **Solution**: Check logcat for stack traces
- Verify all required permissions are granted (check runtime permissions for Android 6.0+)

### WebSocket Connection Issues
- Verify server address and port are correct
- Check that server certificates are valid/trusted
- Verify device can reach the server (check network connectivity)

### Camera Streaming Not Working
- Ensure `INTERNET` permission is granted
- Check that video frames are being received from server
- Verify MJPEG stream is working on server side

## Performance Optimization

### Memory Management
- Images are loaded as bitmaps - consider implementing recycling for old frames
- Use `lifecycleScope.launch` to manage coroutines properly
- Avoid leaking listeners/callbacks

### Network Optimization
- WebSocket connections are persistent - reuse connections
- Audio streaming uses PCM 16-bit - adjust buffer sizes for latency trade-offs
- Video frames may lag on slow networks - consider frame skipping

### Battery Usage
- Audio recording continuously uses battery - only enable when needed
- Consider reducing video resolution on slow networks
- Implement adaptive bitrate if network conditions change

## Continuous Integration (Optional)

### GitHub Actions Setup
Create `.github/workflows/android-build.yml`:

```yaml
name: Android Build

on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Set up JDK
        uses: actions/setup-java@v2
        with:
          java-version: '11'
      - name: Build APK
        run: |
          cd android_app
          ./gradlew assembleDebug
```

## Distribution

### Google Play Store
1. Create signed APK: `./gradlew bundleRelease`
2. Create app listing on Google Play Console
3. Upload AAB (Android App Bundle) to Play Console

### Alternative Distribution
- Share APK directly via GitHub releases
- Use Firebase App Distribution for beta testing

## Resources

- **Android Documentation**: https://developer.android.com/docs
- **Material Design**: https://material.io/design
- **Kotlin**: https://kotlinlang.org/docs
- **Jetpack**: https://developer.android.com/jetpack
- **Retrofit**: https://square.github.io/retrofit/
- **OkHttp**: https://square.github.io/okhttp/

## Support & Contributing

For issues or feature requests related to the Android client:
1. Check existing GitHub issues
2. Create a new issue with detailed description
3. Include device info, Android version, and error logs
4. Submit pull requests for bug fixes/features

