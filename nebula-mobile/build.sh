#!/usr/bin/env bash
# Rebuilds app/libs/nebula-mobile.aar (gomobile bind of this module). Needs Go 1.27 and the NDK.
set -euo pipefail
cd "$(dirname "$0")"
GO="${GO:-$HOME/.local/bin/mise exec go@1.27.4 -- go}"
export GOTOOLCHAIN=local ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}"
export GOBIN="$PWD/.bin" PATH="$PWD/.bin:$PATH"
$GO install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
# gomobile shells out to `go`; put the pinned toolchain on PATH
export JAVA_HOME="${JAVA_HOME:-$($HOME/.local/bin/mise where java@temurin-21)}"
export PATH="$($GO env GOROOT)/bin:$JAVA_HOME/bin:$PATH"
gomobile init
mkdir -p ../app/libs
gomobile bind -target android/arm64,android/arm,android/amd64 -androidapi 26 -javapkg chat.warpsignal \
  -ldflags='-s -w' -trimpath -o ../app/libs/nebula-mobile.aar .
rm -f ../app/libs/nebula-mobile-sources.jar
