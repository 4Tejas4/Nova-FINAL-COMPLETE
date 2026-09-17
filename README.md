# Nova — Local Android Assistant

This project is the final source package assembled around the user's supplied llama.cpp repository.

## What is inside
- `app/`: Nova assistant UI, wake service, task planner/executor, Android capabilities, memory and verification.
- `llama-android/`: Android JNI inference library adapted from the supplied llama.cpp Android example.
- `llama.cpp/`: the complete llama.cpp source tree supplied by the user.

## Local AI architecture
Microphone -> Vosk local speech recognition -> Nova agent -> llama.cpp/Qwen GGUF -> structured action -> Android executor -> verification -> TTS.

No OpenAI, Gemini, Claude, OpenRouter or other cloud LLM is used by the command-processing path in this build.

## Model
Default model: Qwen2.5-0.5B-Instruct Q4_K_M GGUF.
The GGUF is intentionally not bundled in this source ZIP. Nova can download it from Settings or load a GGUF file from phone storage. This keeps the application source package much smaller.

## Android build requirements
- Android SDK compile 36
- Java 17
- Android Gradle Plugin 8.11+
- Kotlin 1.9.22+
- Android NDK with CMake support
- ARM64 (`arm64-v8a`) for the user's Redmi Note 8

The native library's CMake project is under `llama-android/src/main/cpp` and adds the supplied `llama.cpp` source tree directly.

## Important Android limits
A normal Android app cannot bypass OS security. Some operations require permissions, user confirmation, Accessibility, Notification access, or Android settings UI. Nova does not silently bypass those restrictions.

## First setup
1. Open this project in Code On The Go.
2. Sync Gradle.
3. Make sure the Android SDK has the required CMake/NDK packages installed.
4. Build the debug APK.
5. Install Nova and grant microphone permission.
6. Open Nova Settings and download or load the GGUF model.
7. Start Nova and use the local “Hey Nova” wake system.
