<h1 align="center">Transwarp</h1>

<div align="center">

A WireGuard / AmneziaWG client for Android with an LCARS tactical theme and Tailscale-style mesh features
(gofire mesh enrollment, Full Warp exit toggle, Mesh DNS, Sensor Array, Beam, multi-hop).

[Releases](https://github.com/trip1/android/releases)

</div>

## About

Transwarp is a fork of [WG Tunnel](https://github.com/wgtunnel/android) by Zane Schepke (MIT licensed, see
[LICENSE](LICENSE)). It keeps WG Tunnel's auto-tunneling, AmneziaWG, Lockdown and Local Proxy modes and adds the
mesh features above.

## Features

- **Auto-Tunneling:** Automatically activate tunnels based on your device's active network details.
- **Deferred Endpoint Bootstrapping:** Safely resolves endpoints and updates peers after the tunnel is up for better reliability and leak protection on startup.
- **Handshake Monitoring:** Real-time handshake monitoring for instant tunnel health feedback.
- **AmneziaWG Support:** Full support for AmneziaWG 2.0 through 3.1, providing robust censorship protection.
- **Split Tunneling:** Flexible support for routing specific apps or traffic through the VPN.
- **Split & Encrypted DNS:** Resolve DNS through the tunnel using plain DNS, DoT, or DoH, and optionally split by domain suffix (tunnel or system).
- **Local Proxy Mode:** Expose WireGuard tunnels over a local SOCKS5 or HTTP proxy to browsers or firewall apps (like AdGuard).
- **Lockdown Mode:** Advanced in-app kill switch that blocks all traffic while the tunnel is down.
- **Quick Controls:** Quick Settings tile and home screen shortcuts for easy toggling.
- **Remote Control Support:** Intent-based automation for controlling tunnels and auto-tunneling from automation apps (like Tasker).
- **Dynamic DNS Handling:** Automatically detect and update endpoints on server IP changes without requiring a restart.
- **IPv6 Endpoints:** Automatically upgrade to IPv6 endpoints or fall back to IPv4 based on network conditions without requiring a restart.
- **Android TV Support:** Full support for nearly all features on Android TV.

## Building

The app consumes published [core](https://github.com/wgtunnel/core) artifacts from Maven Central by default (`libs.bundles.wgtunnel.core`). That is enough for most app-only work.

```sh
git clone https://github.com/trip1/android
cd android
./gradlew assembleDebug
```

### Local full build (app + core)

To build against a local core checkout (native JNI, backend, parser, hevtunnel), clone **core next to this repo**:

```
parent/
  android/    # this repository
  core/       # https://github.com/wgtunnel/core
```

```sh
cd /path/to/parent
git clone https://github.com/trip1/android
git clone https://github.com/wgtunnel/core
cd android
```

You also need the [core build requirements](https://github.com/wgtunnel/core#requirements) (JDK 21, Android NDK, `make`, a C toolchain).

Then switch Gradle from Maven Central to the composite build:

1. In `settings.gradle.kts`, uncomment the local-dev `includeBuild`:

```kotlin
// Local dev
includeBuild("../core") {
	dependencySubstitution {
		substitute(module("com.wgtunnel.tunnel:backend"))
			.using(project(":backend"))
		substitute(module("com.wgtunnel.tunnel:backend-android-jni"))
			.using(project(":backend-android-jni"))
	}
}
```

2. In `app/build.gradle.kts`, comment out the Maven bundle and uncomment the local-dev implementations:

```kotlin
dependencies {
    implementation(project(":logcatter"))
    implementation(project(":networkmonitor"))
    // implementation(libs.bundles.wgtunnel.core)

    // Local dev
    implementation("com.wgtunnel.tunnel:backend")
    implementation("com.wgtunnel.tunnel:backend-android-jni")
}
```

3. Build from `android/`:

```sh
./gradlew assembleDebug
```

Do not commit those Gradle edits. Restore the commented `includeBuild` and Maven `libs.bundles.wgtunnel.core` dependency before opening a PR.
