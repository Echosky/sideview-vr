# Security policy

## Supported versions

Only the latest release gets fixes while Sideview VR is in beta.

## Reporting a vulnerability

Please report security problems privately through GitHub:
**Security → Report a vulnerability** on this repository. Do not open a public issue.

Useful to include: the version, your phone and Android version, and steps to reproduce.

## What the app touches

- Network: only the SMB, DLNA, HTTP(S) and watch-together addresses you enter or that answer a DLNA
  scan on your local network. No analytics, no telemetry, no update checks.
- SMB passwords are stored in the app's private settings on the phone.
- Offline copies live in the app's own storage and are deleted with the app.
- A local HTTP server bound to 127.0.0.1 streams SMB files to the player; it is not reachable from
  other devices.
- Watch together opens TCP port 47630 on the hosting phone while hosting is on.
