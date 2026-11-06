# Hermes for Android

An unofficial Android client for [Hermes Agent](https://github.com/NousResearch/hermes-agent),
not affiliated with Nous Research.

It talks to your own Hermes the way Hermes Desktop does — through the
dashboard's WebSocket — so the sessions you start on the phone are the same
sessions the CLI and Desktop see, and the other way round. It looks and feels
like the Claude Android app: open it and start typing.

## What you need

- A Hermes dashboard (`hermes dashboard` or `hermes serve`) the phone can
  reach — over Tailscale (plain HTTP is allowed) or behind an HTTPS proxy.
- Password login enabled on the dashboard (`HERMES_DASHBOARD_BASIC_AUTH_*`).
- The address you type into the app listed in the dashboard's `public_url`,
  or Hermes refuses the request.

Tested against Hermes Agent v0.23.0.

## Install

Releases on this repository carry a signed APK. With
[Obtainium](https://github.com/ImranR98/Obtainium), add this repository's URL
and it installs and updates the app from there.

## Build

```sh
cd android
./gradlew test assembleDebug
```

A release build is shrunk with R8. Signing reads `RELEASE_KEYSTORE`,
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`
from the environment; without them `assembleRelease` produces an unsigned APK.
`versionName`/`versionCode` come from `-P` properties (CI passes the release
tag and the commit count); the local default is `dev`/`1`.

End-to-end tests run against a real Hermes in a rootless podman container —
see `e2e/hermes-e2e.sh`.

## License

[GPL-3.0](LICENSE)
