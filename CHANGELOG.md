# Changelog

All notable changes to Octopus Mobile are documented here.
Format loosely follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Added
- **Real logout / session revocation** (server): `users.token_version` column;
  new JWTs carry a `tv` claim and `actor`/WebSocket auth (incl. the per-message
  console re-check) reject a mismatch. `POST /auth/logout` bumps the version
  (revokes every session of that user) and closes their live remote links.
  Tokens issued before this change lack `tv`, count as version 0, and keep
  working until the first logout or ban. Admin ban/unban also bumps the version.

### Changed
- **Revoke/ban closes live links**: revoking a device or banning a user now
  drops the live device and console WebSockets immediately, not just future requests.
- **Video ownership**: video submissions are recorded per user (`video_tasks`);
  polling another user's video id returns 404 without touching the upstream.
- **Encrypted credential storage** (mobile): WebDAV mount passwords and LAN device
  tokens now live in the encrypted store, keyed per mount/device; legacy plaintext is
  migrated on read and cleared only after the encrypted write succeeds. A credential-less
  discovery beacon no longer wipes a verified LAN token for the same device and address.
- **Bearer-token cleartext guard**: the remote client refuses to send its bearer token
  over `ws://` unless the host is loopback, the emulator host, or a private/link-local/`.local`
  address.
- **Legacy tentacle client removed**: the old tentacle client code path is gone.
- **LAN console and MCP lockout**: 10 bad tokens in 60 s from one IP locks that IP out for
  5 minutes (429) on the 9527 control server and the 9528 MCP server; MCP now binds the
  WiFi IP only when LAN mode is on, otherwise loopback. The console screen stream uses
  30-second single-use tickets instead of a token in the URL.
- **Shizuku fallback flagging**: when Shizuku cannot start a process, privileged commands
  now fail with a clear error; file commands still run as the app but are flagged
  `privileged=false`. Root shell dump paths are restricted and arguments quoted.

### Known limitations
- **Legacy stream auth**: `/api/screen/stream` still accepts a bearer header
  without a ticket. Native clients (MjpegImageView, RemoteControlActivity,
  ChatScreen) send the header and rely on it; only browser `<img>` needs the
  one-time ticket. The token never appears in the URL and failures still count
  toward the lockout.
- **Legacy videos**: videos submitted before the `video_tasks` table existed have
  no ownership record and now 404 on poll. They cannot be backfilled (no
  video-to-user data was ever stored), so no admin/CLI backfill exists; users must
  resubmit the generation.

## [1.0.0] — 2026-07-05

First stable release (`versionCode 9`). Builds on the 0.0.1 test build with a
round of intelligence upgrades to the in-app assistant, a multi-character system,
high-framerate remote screen capture, and browser/glass-theme visual polish.

### Added
- **Multi-turn conversation context** (`ConversationContext` + `ChatAgentBridge`):
  the in-app chat now carries a summary of recent turns into each task, so
  follow-ups like “换成蓝牙的” resolve against what was just said instead of being
  treated as a brand-new, context-free instruction.
- **Long-term memory, now wired into the chat surface** (`MemoryStore`): the chat
  path reads *and* writes cross-session memory (preferences/facts) the same way the
  messaging channels always did. Extraction is upgraded from 4 regex patterns to
  a zero-extra-cost `MEMO:` annotation the main model emits and we harvest; regex
  stays as a fallback. Injection is budgeted and ranked (preferences first), and
  the eviction bug that discarded the *newest* memory instead of the least-used is
  fixed.
- **Per-character conversations** (`0a58299`): switch the assistant’s character in
  chat; sessions and history are fully isolated per character (TV mode included).
- **High-framerate remote screen capture** (`4f8700b`): `MediaProjection`-based
  capture lifts the remote-control mirror from 2–5 fps to 20–30 fps.
- **“TV mode”** (`78298b3`): the landscape “local virtual PC” is renamed TV mode
  and injects the current character’s persona into the conversation.

### Fixed
- **`extractFromTask` no longer mines the agent’s own reply** — first-person text in
  the assistant’s answer (“我是你的手机助手”) was being stored as a *user* fact.
- **Glass theme**: transparency/highlight settings now actually take effect; square
  posts use a solid background instead of tracking the glass alpha (`ec0b941`).
- **Browser glass polish**: address-bar outline unified to the same soft-white as
  other glass components (was theme purple); home search bar made properly
  translucent (was an opaque 82% white “bar”), and its placeholder text darkened
  from `TextMuted` so it’s legible on the light field.

## [0.0.1] — 2026-07-04

> **Versioning reset**: the 0.0.1 test build restarted the display version from
> the internal 0.0.2–0.0.7 line. 1.0.0 above is the first stable public release —
> `versionCode 9`, so upgrades from 0.0.1 (vc8) or any earlier internal build
> keep working.

A round focused on the **mini-app community square (submit → auto-review →
browse → install)**, **zero-touch Shizuku setup**, **OTA self-update + crash
reporting** (both work pre-login), and a large gate-hygiene pass (compile fix,
45 ja + 42 zh translations, R8 keeps, detekt/lint back to green).

### Added
- **In-app OTA update**: silent check on launch plus a manual "check update" row
  in Settings (`AppUpdater` + `AppUpdateHost`); server serves
  `GET /app/latest` / `/app/latest.apk` as controlled static distribution — ops
  drops a signed universal APK at `APP_UPDATE_APK_PATH` and declares
  `APP_UPDATE_VERSION_CODE/_VERSION_NAME/_NOTES/_FORCE`; signing keys never
  touch the server. Unconfigured ⇒ `versionCode=0` ⇒ clients see "up to date".
- **Crash reporting that works before login/pairing** (`CrashReporter` +
  public rate-limited `POST /crash/report`): an uncaught-exception handler
  writes the crash to disk synchronously (no network in the crashing thread),
  uploads pending files in the background on next launch, with count/age
  retention caps so an unreachable server can't pile up files on low-storage
  devices.
- **Mini-app community square, full submit→review→install loop**: share a
  generated mini-app to the square (`SquarePublisher`), server-side
  `POST /square/publish` with auto-review that only auto-rejects or flags risk —
  **never auto-approves**; browse and install approved community mini-apps in the
  client (`MiniAppMarketplaceActivity` + `CommunitySquareApi`). Consumer endpoints
  live under `/square/assets` (not `/api/v1/registry/assets`, which an earlier
  nginx `location` rule routes to the enterprise registry — verified on prod).
- **Fully automatic Shizuku setup**: bundled libadb wireless-ADB pairing plus a
  visual-Agent skill playbook scrapes the pairing dialog — no PC required.
- **Generate-app UX**: the agent now asks clarifying questions *before* generating
  a mini-app and streams stage-by-stage progress while building.

### Changed
- **Live-control overlay is background-only now**: while the app is foreground the
  chat's inline event stream (tool cards + thinking progress) is the single source
  of progress; the floating stop-bar only appears once the Agent moves to another
  app (driven by `ProcessLifecycleOwner`), and retracts on return.
- **Desktop/browser-home visual pass**: full-bleed wallpaper with immersive status
  bar, dark theme actually applied, discover page split into "my apps" / "web".
  static analysis wired as a **baseline ratchet** — the 2,631 pre-existing findings
  are grandfathered in `app/detekt-baseline.xml`, and only *new* issues fail the
  build. Added to CI (`ci.yml`) alongside the existing Android Lint ratchet.
  Verified on the current Gradle 9.3.1 / AGP 9.1 / Kotlin 2.1.20 toolchain, and
  proven to actually fail on a newly-introduced empty-catch-block.

### Changed
- **Docs honesty pass** (`CODE_WIKI.md`): the top-level feature blurbs and the
  core-execution-flow narrative previously described `BrainModeSelector` local/remote
  routing, outbound task delegation to the Runtime, and B3 `deepEvolve`/`CanaryManager`
  self-evolution as if live. They are now marked with their real wiring status
  (inbound remote control is real & gated; outbound delegation is an unwired `TODO`;
  only B1/B2 evolution is active) to match the README implementation-status table.

### Security
- **Channel ACL now authorizes by message author, not by conversation, and is
  race-free** (`ChannelManager`/`ChannelSetup` + all 6 handlers). The authorization
  subject (`senderId`) now travels atomically *with* each message through
  `dispatchMessage(...)` instead of being re-read from a shared `getLastSenderId()`
  field after the fact — closing a TOCTOU where concurrent messages could authorize
  the wrong sender. Discord (`author.id`) and Telegram (`from.id`) previously
  authorized by **channel/chat id**, so *any* group member passed ACL once the
  conversation was bound; they now authorize by the actual author. Reply routing
  still uses the conversation id (correctly decoupled from the auth subject).
  Regression test: `ChannelDispatchSenderIdTest`. *Residual:* DingTalk-group and
  QQ-group member-level granularity is unchanged (still conversation-scoped for
  groups) — tracked as a follow-up.
- **Mother-brain insecure-transport toggle can no longer be flipped remotely**
  (`DualConfigWriter`): `KEY_OCTOPUS_ALLOW_INSECURE_RUNTIME` was missing from the
  remote-write blocklist, so a malicious/MITM'd Runtime could set it to `true` and
  downgrade the WebSocket to cleartext `ws://` (bypassing `MobileRuntimeSecurity`,
  which otherwise blocks remote cleartext). It is now blocked alongside the other
  security-policy switches — the flag must be enabled locally.

### Fixed
- **Accessibility service no longer dies on swipe-back**: swiping back from the
  task root now moves the app to background instead of finishing the process (and
  killing the accessibility service with it).
- **Share-to-square used the wrong token source** — now uses the login-state
  `AccountStore.token`.
- **Release-only silent breakage of the square**: the new `registry`-package Gson
  wire DTOs (`CommunityMiniApp` etc.) had no R8 keep, so browse/install would
  parse to empty in release builds (fine in debug); kept the package alongside the
  existing account/screen DTO keeps. Also backfilled 45 missing Japanese
  translations that tripped the `MissingTranslation` lint gate.

### Performance
- **Streaming chat**: latch timeout guard, `cancelToken` now interrupts in-flight
  streams, and token batches are merged before recomposition.

### Reliability
- **VLM goal-verification can no longer hang the agent loop**
  (`DefaultAgentService.shouldRepairForGoal`): the `runBlocking { GoalVerifier.verify() }`
  call is now wrapped in `withTimeoutOrNull(35s)`. A stuck VLM network call previously
  blocked the single execution thread indefinitely; it now falls back to the existing
  fail-open path (treat as "can't verify", never blocks completion) after a hard ceiling.
- **Server multi-worker footgun documented** (`server/README.md`): the previous advice
  to "add gunicorn workers for high concurrency" silently breaks the in-process rate
  limiter (`_rl`) and `RemoteRelayHub` — each worker holds its own copy. The note now
  requires externalizing both to Redis *before* scaling horizontally.

## [0.0.2] — 2026-06-14

A round focused on **messaging channels, feature discoverability, an iOS-flavored
visual pass, plain-language wording, and APK slimming / 32-bit support**.

### Added
- **Messaging channels hub** (`ChannelsActivity`): a single screen listing all
  channels with connect status. Fixes the previous bug where Settings → Channels
  opened a config page that instantly closed (it was launched without a channel
  type). DingTalk / Feishu / QQ / Discord / Telegram all configurable again.
- **WeChat QR login** (`WeChatLoginActivity`): scan-to-login flow wired to the
  existing `WeChatApiClient` (`getQrCode` → poll → save token → reconnect).
- **"Features" bottom tab** (`FeatureHubScreen`): restores access to screens that
  became unreachable when Discover turned into the browser home — Skills, Browser
  Plugins, Routines, Cloud Drive, Memory, Video Library, Multi-Window,
  Self-Evolution, Trust Center.
- **Browser Settings page** (`BrowserSettingsActivity`): default search engine
  picker, engine/kernel info, and a shortcut to browser plugins. Honest controls
  only (no placeholder toggles).
- **Trilingual strings (EN / ZH / JA)** for all new and previously-hardcoded
  user-facing screens.

### Changed
- **iOS-flavored visual pass.** Consistent solid rounded-square colored icon
  tiles (white glyphs) across the Features hub, Browser Settings, and Settings
  rows. Browser favorites now render as white "app-icon" tiles, fixing the
  GitHub (and other dark-mark) logos being invisible on the dark surface.
- **Simplified remote-connection settings** for non-technical users: a single
  plain "Remote-control your PC" entry, with the host connection / RPC modes /
  LAN remote desktop tucked into a collapsed **Advanced** group. Nothing removed,
  just hidden by default.
- **Plain-language wording.** Replaced internal jargon in user-facing text —
  「母体」/ "Parent" / "Remote Brain" / "mother device" → 「主机」/ "host" /
  "Remote Execution" — consistently across EN / ZH / JA.

### Fixed
- **Security (ConfigServer / web console):**
  - Path traversal on `/api/files/browse` and `/api/files/search` (now restricted
    to `/sdcard`, with `..` and injection chars rejected); unified the same guard
    across download / upload / delete.
  - Plaintext secret echo: `GET /api/llm` and `/api/channels` now mask secrets;
    the POST handlers already skip masked values, so saving via the web UI no
    longer overwrites a real key with the mask.
- **Crashes / races:** `H264Decoder.stop()` now joins the worker before releasing
  the codec; `DefaultAgentService` guards a null executor; `ChatAgentBridge` uses
  a shared busy lock so the web console and the in-app chat can't clobber each
  other; `PcRemoteWebrtcActivity` survives a missing system WebView.
- **i18n:** all user-facing Chinese that was hardcoded in Kotlin now resolves per
  locale (previously stuck on Chinese regardless of device language).
- **Release-only favicon bug:** added Coil R8 keep rules so browser favorites and
  search-engine icons load in the minified release build (they were blank).

### Build
- **Slimmed the APK ~40 MB** by dropping the bundled mpv/ffmpeg native libs — the
  player was a non-functional stub, so the ~25 MB of `.so` (plus its dex) was dead
  weight. Video playback, when implemented, will fetch the libs on demand.
- **ABI splits** (`arm64-v8a` + `armeabi-v7a`) so 32-bit devices are supported
  without bloating the 64-bit package:
  - `arm64-v8a` ≈ **237 MB** (most phones / TV boxes)
  - `armeabi-v7a` ≈ **201 MB** (older 32-bit devices)
  - No combined "universal" APK. For store distribution, ship an AAB instead.

### Notes
- ~~The remaining APK size is dominated by the bundled **GeckoView** engine~~
  **GeckoView has been removed** (saved ~180 MB: `libxul.so` 144 MB + `omni.ja`
  13 MB + various `.so` files). The browser now uses the system WebView
  (Chromium engine, 0 APK size impact). Extension support has been replaced
  with a custom injectable plugin system (`evaluateJavascript` +
  `shouldInterceptRequest`).
- Account email/SMS-code login and the hosted model-relay backend landed in the
  same period (separate workstream).
