#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
#
# Fetches IB's official TWS API installer by pinned SHA-256 into .cache/ (never
# committed), runs it unattended into a throwaway directory and prints the path
# of TwsApi.jar. Nothing of IB's is copied into this repository or its jar.
#
# NOT VERIFIED: written without being able to run IB's installer. It runs IB's
# code, so use a throwaway container or VM. Needs: curl, sha256sum, find.
#
# Usage: scripts/fetch-twsapi.sh            -> prints the jar path
#        TWSAPI_SHA256=... TWSAPI_URL=... scripts/fetch-twsapi.sh   (to pin another build)
set -eu

URL="${TWSAPI_URL:-https://download2.interactivebrokers.com/installers/tws-api/stable/tws-api-stable-linux-x64.sh}"
SHA256="${TWSAPI_SHA256:-02247a284feda91da4904e5e3d851f20b272124aa91c20ca6097112521ab61df}"
here="$(cd "$(dirname "$0")/.." && pwd)"
cache="${TWSAPI_CACHE:-$here/.cache/twsapi}"
installer="$cache/installer.sh"
dest="$cache/install"

mkdir -p "$cache"
if [ ! -f "$installer" ] || ! echo "$SHA256  $installer" | sha256sum -c - >/dev/null 2>&1; then
  curl -fsSL --proto '=https' --tlsv1.2 -o "$installer.part" "$URL"
  if ! echo "$SHA256  $installer.part" | sha256sum -c - >/dev/null 2>&1; then
    echo "SHA-256 mismatch for $URL: IB replaced the file. Read the new licence, then update the pin." >&2
    rm -f "$installer.part"
    exit 1
  fi
  mv "$installer.part" "$installer"
fi

if [ ! -d "$dest" ]; then
  # install4j unattended mode; the installer shows no licence page in -q mode,
  # so the licence file it installs must be read and quoted in LICENSE-IB-NOTES.md
  sh "$installer" -q -dir "$dest" >&2
fi

jar="$(find "$dest" -name TwsApi.jar | head -n 1)"
if [ -z "$jar" ]; then
  echo "TwsApi.jar not found under $dest" >&2
  exit 1
fi
echo "$jar"
