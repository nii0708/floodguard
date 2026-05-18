# FloodGuard

Android flood-rescue indoor navigation using ARCore VIO for positioning and on-device Gemma 4 E2B (via LiteRT-LM) for scene understanding. Gives rescue teams spatial memory, loop detection, and hands-free Indonesian voice control — entirely offline.

## Requirements

- Android device, **arm64-v8a**, Android 8.0+ (API 26+)
- ARCore-supported device (optional — falls back to sensor PDR if unavailable)
- ~3 GB free storage for the Gemma 4 E2B model
- Android Studio Hedgehog or newer
- JDK 17 (Automatically configured via Gradle Toolchain)

## Installation

### 1. Clone the repo

```bash
git clone https://github.com/nii0708/floodguard.git
cd floodguard
```

### 2. Open in Android Studio

File → Open → select the `floodguard` folder. Wait for Gradle sync to finish.

### 3. Download the Gemma 4 E2B model

The model is **not bundled**. You can download it in-app on first launch, or manually via ADB for faster setup.

1. Download the `gemma-4-E2B-it.litertlm` file from [Hugging Face](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/main).
2. Connect your device and push the model:

```bash
adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.floodguard.rescue/files/models/
```

> The app validates the file on launch via a magic-header check. If the path or header is wrong, it will redirect to the model setup screen.

### 4. Build and install

```bash
# Debug build
./gradlew installDebug

# Or from Android Studio: Run → Run 'app'
```

### 5. Grant permissions

On first launch, accept the prompts for:
- **Camera** — required for ARCore
- **Activity Recognition** — required for step detector (PDR fallback)
- **Microphone** — required for voice commands

## Build variants

| Command | Output |
|---|---|
| `./gradlew assembleDebug` | Debug APK |
| `./gradlew assembleRelease` | Release APK (ProGuard enabled) |
| `./gradlew compileDebugKotlin` | Compile check only, no APK |
| `./gradlew clean assembleDebug` | Clean build |

## How it works

1. **Start mission** — 5-second countdown, then ARCore begins tracking.
2. **Voice commands** — press the on-screen mic button (or volume button) and speak in Indonesian. Gemma classifies the command:
   - *"Sudah pernah ke sini?"* → checks trajectory for revisits and speaks the result.
   - *"Tandai lokasi ini"* → captures the current ARCore frame, sends it to Gemma for a scene description, and saves a landmark.
3. **Revisit alert** — when you return within 1.8 m of a prior position, the app speaks an alert and shows a banner.
4. **Stop mission** → summary screen with landmark list, 2D map, and JSON export to `Downloads/floodguard/`.

## Credits

Built with [Claude Code](https://claude.ai/code) and Gemini (Android Studio Agents).
