#!/usr/bin/env bash
#
# Build the app and push it to a connected Android device.
#
#   ./deploy.sh                 build debug, install, launch
#   ./deploy.sh --test          run unit tests first, abort if they fail
#   ./deploy.sh --clear         wipe app data before launching (fresh-install behaviour)
#   ./deploy.sh --no-launch     install but don't start the app
#   ./deploy.sh --logcat        follow the app's log after launching (Ctrl-C to stop)
#   ./deploy.sh --release       build the signed release APK (does not install it)
#   ./deploy.sh --release --install
#                               also install it. DESTRUCTIVE: debug and release are signed
#                               with different keys, so Android requires an uninstall first,
#                               which erases all reports, receipts and images on the device.
#   ./deploy.sh --uninstall     remove the app from the device and exit
#
# Target a specific device when more than one is attached:
#   ANDROID_SERIAL=67280DLKY0027G ./deploy.sh

set -euo pipefail

# Resolve this script's own path before cd, so --help can still read its header comment
# when the script is invoked by path from another directory.
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
cd "$(dirname "${BASH_SOURCE[0]}")"

PACKAGE="cc.rocketscience.receipts"
ACTIVITY=".MainActivity"

run_tests=false
do_launch=true
do_clear=false
do_logcat=false
do_release=false
do_install_release=false
do_uninstall=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --test)      run_tests=true ;;
        --clear)     do_clear=true ;;
        --no-launch) do_launch=false ;;
        --logcat)    do_logcat=true ;;
        --release)   do_release=true ;;
        --install)   do_install_release=true ;;
        --uninstall) do_uninstall=true ;;
        # Print the header comment block, stopping at the first non-comment line.
        -h|--help)   awk 'NR>1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "$SELF"; exit 0 ;;
        *)           echo "unknown option: $1  (try --help)" >&2; exit 2 ;;
    esac
    shift
done

# ---------- colours (only when attached to a terminal) ----------
if [[ -t 1 ]]; then
    bold=$'\033[1m'; red=$'\033[31m'; green=$'\033[32m'; yellow=$'\033[33m'; dim=$'\033[2m'; off=$'\033[0m'
else
    bold=''; red=''; green=''; yellow=''; dim=''; off=''
fi
step() { printf '%s==>%s %s%s%s\n' "$green" "$off" "$bold" "$*" "$off"; }
warn() { printf '%s==>%s %s\n' "$yellow" "$off" "$*" >&2; }
die()  { printf '%s==>%s %s\n' "$red" "$off" "$*" >&2; exit 1; }

# ---------- locate adb ----------
find_adb() {
    if [[ -n "${ADB:-}" && -x "${ADB}" ]]; then echo "$ADB"; return; fi
    local candidates=(
        "${ANDROID_HOME:-}/platform-tools/adb"
        "${ANDROID_SDK_ROOT:-}/platform-tools/adb"
        "$HOME/Library/Android/sdk/platform-tools/adb"
    )
    for c in "${candidates[@]}"; do
        [[ -n "$c" && -x "$c" ]] && { echo "$c"; return; }
    done
    command -v adb 2>/dev/null && return
    die "adb not found. Set ANDROID_HOME, or install: brew install --cask android-platform-tools"
}
ADB_BIN="$(find_adb)"

# ---------- resolve exactly one device ----------
# Fields: <serial> <state>. Anything not "device" is unusable, and each bad state
# has a different fix, so report which one it is rather than a generic failure.
require_device() {
    local lines serial state count
    lines="$("$ADB_BIN" devices | awk 'NR>1 && NF>=2 {print $1, $2}')"
    [[ -z "$lines" ]] && die "No device attached. Plug in the phone and enable USB debugging."

    if [[ -n "${ANDROID_SERIAL:-}" ]]; then
        state="$(echo "$lines" | awk -v s="$ANDROID_SERIAL" '$1==s {print $2}')"
        [[ -z "$state" ]] && die "ANDROID_SERIAL=$ANDROID_SERIAL is not attached. Attached:"$'\n'"$lines"
        serial="$ANDROID_SERIAL"
    else
        count="$(echo "$lines" | wc -l | tr -d ' ')"
        if [[ "$count" -gt 1 ]]; then
            warn "More than one device attached; pick one with ANDROID_SERIAL=..."
            echo "$lines" >&2
            exit 1
        fi
        serial="$(echo "$lines" | awk '{print $1}')"
        state="$(echo "$lines" | awk '{print $2}')"
    fi

    case "$state" in
        device) ;;
        unauthorized)
            die "Device $serial is unauthorized. Unlock the phone and tap Allow on the
    'Allow USB debugging?' prompt (tick 'Always allow from this computer')." ;;
        offline)
            die "Device $serial is offline. Unplug and replug it, or run: $ADB_BIN kill-server" ;;
        *)
            die "Device $serial is in state '$state', which is not usable." ;;
    esac

    DEVICE_SERIAL="$serial"
    export ANDROID_SERIAL="$serial"
}

adb() { "$ADB_BIN" -s "$DEVICE_SERIAL" "$@"; }

# ---------- release: build only, since it is not signed yet ----------
if $do_release; then
    step "Building release APK"
    ./gradlew :app:assembleRelease
    apk="$(find app/build/outputs/apk/release -name 'app-release*.apk' | head -1)"
    [[ -f "$apk" ]] || die "no release APK was produced"
    printf '%s\n' "${dim}$(ls -lh "$apk" | awk '{print $5, $9}')${off}"

    if [[ "$apk" == *unsigned* ]]; then
        warn "This APK is unsigned: keystore.properties is missing or points at a keystore"
        warn "that is not there. See keystore.properties.example. It cannot be installed."
        exit 0
    fi

    if ! $do_install_release; then
        step "Signed. Not installing (pass --install to install it)."
        exit 0
    fi

    require_device

    # An in-place upgrade only fails when the signing keys differ. If a release build is
    # already installed, this APK carries the same signature and upgrades cleanly with data
    # intact, so there is no reason to make the user type ERASE for it.
    if adb install -r "$apk" 2>/tmp/receipts-install-err; then
        step "Upgraded in place; app data preserved"
    else
        if ! grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE\|signatures do not match" /tmp/receipts-install-err; then
            cat /tmp/receipts-install-err >&2
            die "Install failed."
        fi
        # Signatures differ, which means a debug build is installed. Android cannot upgrade
        # across signing keys, so the existing app has to go — taking the database and every
        # stored image with it. Make the cost explicit.
        warn "A differently-signed build (debug) is installed, so it must be removed first."
        warn "That ERASES every report, receipt and image currently on ${DEVICE_SERIAL}."
        warn "Back it up first:  ./device-data.sh backup"
        printf 'Type ERASE to continue: '
        read -r confirmation
        if [[ "$confirmation" != "ERASE" ]]; then
            die "Cancelled; nothing was changed on the device."
        fi
        step "Uninstalling the existing build"
        adb uninstall "$PACKAGE" || warn "was not installed"
        step "Installing the release APK"
        adb install "$apk"
    fi
    step "Launching $PACKAGE"
    adb shell am start -S -W -n "${PACKAGE}/${ACTIVITY}" | tr -d '\r' | sed "s/^/${dim}/;s/$/${off}/"
    exit 0
fi

require_device
model="$(adb shell getprop ro.product.model | tr -d '\r')"
release="$(adb shell getprop ro.build.version.release | tr -d '\r')"
sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
step "Device: ${model} — Android ${release} (API ${sdk}) [${DEVICE_SERIAL}]"

if $do_uninstall; then
    step "Uninstalling $PACKAGE"
    adb uninstall "$PACKAGE" || warn "was not installed"
    exit 0
fi

if $run_tests; then
    step "Running unit tests"
    ./gradlew :app:testDebugUnitTest
fi

step "Building and installing debug APK"
if ! ./gradlew :app:installDebug 2>&1 | tee /tmp/receipts-debug-install.log; then
    if grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE\|signatures do not match" /tmp/receipts-debug-install.log; then
        warn ""
        warn "A release-signed build of $PACKAGE is installed, and Android cannot replace it"
        warn "with a debug build in place. To switch back to debug builds:"
        warn "  1. Back up in the app:  Settings -> Back up to a file"
        warn "  2. adb uninstall $PACKAGE"
        warn "  3. ./deploy.sh"
        warn "  4. Restore in the app:  Settings -> Restore from a file"
    fi
    die "Install failed."
fi

apk="app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$apk" ]] && printf '%s\n' "${dim}$(ls -lh "$apk" | awk '{print $5, $9}')${off}"

if $do_clear; then
    step "Clearing app data"
    adb shell pm clear "$PACKAGE" >/dev/null
fi

if $do_launch; then
    step "Launching $PACKAGE"
    # -S forces a cold restart so the run always reflects the APK just installed.
    adb shell am start -S -W -n "${PACKAGE}/${ACTIVITY}" | sed "s/^/${dim}/;s/$/${off}/" | tr -d '\r'

    sleep 2
    if crash="$(adb logcat -d -b crash -t 200 2>/dev/null | grep -F "$PACKAGE" || true)"; then
        if [[ -n "$crash" ]]; then
            warn "Crash log mentions $PACKAGE:"
            echo "$crash" | tail -20 >&2
        fi
    fi
fi

if $do_logcat; then
    pid="$(adb shell pidof "$PACKAGE" | tr -d '\r' || true)"
    if [[ -z "$pid" ]]; then
        warn "App is not running; cannot follow its log."
    else
        step "Following logcat for pid $pid (Ctrl-C to stop)"
        adb logcat --pid="$pid"
    fi
fi

step "Done"
