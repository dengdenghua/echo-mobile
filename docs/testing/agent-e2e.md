# Agent end-to-end test (emulator)

`app/src/androidTest/java/com/apk/claw/android/agent/e2e/AgentDeviceTaskE2ETest.kt` runs one real
agent task on an emulator, through the same path a user takes:

1. It types an instruction into the chat screen and taps send.
2. The chat runs the real agent loop: `ChatAgentBridge` → `DefaultAgentService` → `ToolRegistry`
   (every gate) → `ClawAccessibilityService`.
3. The model is `ScriptedAgentLlm`, a deterministic OpenAI-compatible stub served by MockWebServer
   on `127.0.0.1` inside the test process. It decides each step only from the tool results the agent
   sends back:

   | Step | Tool | What it proves |
   |---|---|---|
   | 1 | `open_app` com.android.settings | the agent can leave the app |
   | 2 | `scroll_to_find` "About emulated device" | scrolls with accessibility gestures and returns the item's centre |
   | 3 | `tap` that centre | the gesture goes through the accessibility service |
   | 4 | `get_screen_info` | the About screen's tree comes from the accessibility service |
   | 5 | `input_text` a fake `sk-…` API key | a leak a prompt-injected model might try; **SafetyGate must block it** |
   | 6 | `finish` with the device name read in step 4 | the answer comes back to the chat |

The test then checks that:

- the device name the agent reported equals the real one (`Settings.Global.DEVICE_NAME`, else `Build.MODEL`);
- the tools ran in that order, from the `local` source, and each one succeeded except the leak;
- the leak was blocked by SafetyGate (`blockedBy = "safety"`, a `ToolBlockedEvent`), its error reached the
  model, and the key never reached the screen or the action timeline;
- ToolCallGuardrail observed every call that ran;
- the answer is shown in the chat.

It takes about 20 s. With the final stub it passed 5 runs out of 5 on `Echo_Interconnect_API35` (Android 15, x86_64, English
system language) on 2026-10-14.

## What it changes on the device, and puts back

- It turns on the app's accessibility service with `settings put secure enabled_accessibility_services`
  and `accessibility_enabled 1`.
- It points the OpenAI model settings at the stub, signs out of the account for the run (the platform
  relay would bypass the stub), and turns on local mode.
- It selects the phone as the chat target and sets the permission mode to DEFAULT.

All of these are restored in `@After`. The test also force-stops Settings before and after the run.

## Run it

It needs a running emulator or a test device. It is **not** part of CI, because CI has no emulator.

Start the emulator. The SDK must be on an ASCII path; the emulator does not start from a path with
non-ASCII characters.

```bash
emulator -avd Echo_Interconnect_API35 -gpu swiftshader_indirect -crash-report-mode disabled -no-snapshot-save
```

Then build both APKs, install them and run the class (from the repo root):

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/OctopusMobile_v*_x86_64.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
  -e class com.apk.claw.android.agent.e2e.AgentDeviceTaskE2ETest \
  com.octopus.mobile.test/androidx.test.runner.AndroidJUnitRunner
```

On Git Bash for Windows, set `MSYS_NO_PATHCONV=1` first, so the shell does not rewrite the class path.

`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.apk.claw.android.agent.e2e.AgentDeviceTaskE2ETest`
also works, but it uninstalls the app afterwards, which wipes its data.

Each run logs under the tag `EchoAgentE2E`: every step the stub chose, the tool results, and the
action timeline. Read them with `adb logcat -s EchoAgentE2E`.

## Assumptions

- The system language is English, so that Settings shows "About emulated device" and "Device name". On
  a phone, change `ABOUT_ITEM` (for example to "About phone").
- No account is needed. Shizuku and root must not be set up, so the taps and tree reads use the
  accessibility service.
- The tools used must be enabled in Skills; the test fails with a clear message if one is turned off.
