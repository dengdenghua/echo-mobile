# MCP recovery validation

Failed startup now retries with bounded exponential delays, including a port
collision during network rebinding. Explicit stop cancels pending retries.
Authorization failures close the HTTP connection because their rejected POST
body is unread; those bytes must not be parsed as a subsequent request.

Windows validation on 2026-10-11 used JDK 21 and:

```powershell
& scripts/dev-windows/run-gradle.ps1 -JavaHome '<JDK 21 directory>' `
  -GradleArgs @('testDebugUnitTest', 'detekt', 'assembleDebug', 'lintDebug')
```

All 1,493 unit tests passed. Detekt, assembleDebug and lintDebug passed; lint
retains the repository's existing baseline. Recovery tests occupy and release
a real TCP port under Robolectric. Authorization tests use persistent HTTP
clients without transparent retries and repeat the lockout/recovery cycle.

Physical Android WiFi/LAN acceptance remains pending: the user confirmed no
device is currently available. Robolectric checks do not certify WiFi IP changes
or real Android service lifecycle behavior. With a device, verify LAN enable/
disable, WiFi IP changes, service restart, port collision recovery, and explicit
stop after a failed bind.
