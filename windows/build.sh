#!/usr/bin/env bash
# Builds dist/Vaier-windows.zip: Vaier.exe plus WireGuard's tunnel.dll (cross-compiled from a pinned
# commit) and the signed wireguard.dll driver library. Needs Go, x86_64-w64-mingw32-gcc and .NET 10.
set -euo pipefail

WIREGUARD_WINDOWS_COMMIT=6ece77bc487c8aa697e3c092197621c4f3e5ccb8
WIREGUARD_NT_VERSION=1.1
WIREGUARD_NT_SHA256=dceb30a9bc4be48cce0f74160fc88a585a2c2627366e8f846fc6658f9038dace

here="$(cd "$(dirname "$0")" && pwd)"
work="${VAIER_WINDOWS_WORK:-$HOME/win-build}"
dist="$here/dist"
export PATH="$work/go/bin:$HOME/.dotnet:$PATH" DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1
mkdir -p "$work" "$dist"

if [ ! -d "$work/wireguard-windows" ]; then
  git clone -q https://git.zx2c4.com/wireguard-windows "$work/wireguard-windows"
fi
git -C "$work/wireguard-windows" fetch -q origin
git -C "$work/wireguard-windows" checkout -q "$WIREGUARD_WINDOWS_COMMIT"

echo "[+] tunnel.dll"
(cd "$work/wireguard-windows" && GOOS=windows GOARCH=amd64 CGO_ENABLED=1 CC=x86_64-w64-mingw32-gcc \
  CGO_CFLAGS="-O3 -Wall -Wno-unused-function -Wno-switch -std=gnu11 -DWINVER=0x0A00" \
  go build -overlay .overlay/overlay.json -buildmode c-shared -ldflags="-w -s" -trimpath \
  -o "$work/tunnel.dll" ./embeddable-dll-service)

echo "[+] wireguard.dll"
nt="$work/wireguard-nt-$WIREGUARD_NT_VERSION.zip"
[ -f "$nt" ] || curl -sSLo "$nt" "https://download.wireguard.com/wireguard-nt/wireguard-nt-$WIREGUARD_NT_VERSION.zip"
echo "$WIREGUARD_NT_SHA256  $nt" | sha256sum -c --quiet

echo "[+] Vaier.exe"
dotnet test "$here/Vaier.Core.Tests" --nologo -v quiet
rm -rf "$work/publish"
# VAIER_SELF_CONTAINED=false leaves the .NET runtime out: small enough to hand over, but needs it installed.
dotnet publish "$here/Vaier.App" -c Release -o "$work/publish" --nologo -v quiet \
  -p:SelfContained="${VAIER_SELF_CONTAINED:-true}"

stage="$work/stage/Vaier"
rm -rf "$work/stage" && mkdir -p "$stage"
cp "$work/publish/Vaier.exe" "$work/tunnel.dll" "$stage/"
unzip -q -j -o "$nt" wireguard-nt/bin/amd64/wireguard.dll -d "$stage"
unzip -q -p "$nt" wireguard-nt/LICENSE.txt > "$stage/wireguard-nt-LICENSE.txt"
rm -f "$dist/Vaier-windows.zip"
(cd "$work/stage" && zip -q -r "$dist/Vaier-windows.zip" Vaier)
echo "[+] $dist/Vaier-windows.zip"
