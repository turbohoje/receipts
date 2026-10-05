#!/usr/bin/env bash
#
# Build the app and run it on a simulator.
#
#   ./run.sh                    build, boot a simulator, install, launch (iPhone)
#   ./run.sh --iphone           an iPhone simulator, the default
#   ./run.sh --ipad             an iPad simulator, for the split view
#   ./run.sh --sim "iPhone 17"  any other simulator, matched by name
#   ./run.sh --hardware         a real iPhone or iPad plugged in over USB
#
# --hardware needs three things the simulator does not:
#   1. the device on iOS 27 or newer (the deployment target)
#   2. Developer Mode on: Settings > Privacy & Security > Developer Mode
#   3. an Apple ID in Xcode > Settings > Accounts; a free one is enough
# Set DEVELOPMENT_TEAM=XXXXXXXXXX if the Apple ID has more than one team.
#   ./run.sh --demo             seed sample data (never touches a real run's store)
#   ./run.sh --shot out.png     screenshot after launching
#   ./run.sh --checks           run the shared core's checks first, abort if they fail
#   ./run.sh --clean            wipe the app's data before launching
#   ./run.sh --list             list available simulators and exit
#
# The Android equivalent is android/deploy.sh.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

BUNDLE_ID="cc.rocketscience.receipts"
SCHEME="RSReceipts"
PROJECT="RSReceipts/RSReceipts.xcodeproj"
DERIVED="${TMPDIR:-/tmp}/rsreceipts-dd"

# xcode-select still points at the Command Line Tools on this machine, and switching it needs
# sudo. DEVELOPER_DIR overrides the selection per-process, so nothing global has to change.
if [[ -z "${DEVELOPER_DIR:-}" && -d /Applications/Xcode.app ]]; then
    export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
fi

IPHONE="iPhone 18 Pro"
IPAD="iPad Pro 13-inch (M5)"

device="$IPHONE"
demo=false; shot=""; checks=false; clean=false; hardware=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --iphone)  device="$IPHONE"; hardware=false ;;
        --ipad)    device="$IPAD"; hardware=false ;;
        --sim)     device="$2"; hardware=false; shift ;;
        --hardware) hardware=true ;;
        --demo)    demo=true ;;
        --shot)    shot="$2"; shift ;;
        --checks)  checks=true ;;
        --clean)   clean=true ;;
        --list)    xcrun simctl list devices available; exit 0 ;;
        -h|--help) awk 'NR>1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
        *)         echo "unknown option: $1  (try --help)" >&2; exit 2 ;;
    esac
    shift
done

if [[ -t 1 ]]; then
    bold=$'\033[1m'; red=$'\033[31m'; green=$'\033[32m'; dim=$'\033[2m'; off=$'\033[0m'
else
    bold=''; red=''; green=''; dim=''; off=''
fi
step() { printf '%s==>%s %s%s%s\n' "$green" "$off" "$bold" "$*" "$off"; }
die()  { printf '%s==>%s %s\n' "$red" "$off" "$*" >&2; exit 1; }

command -v xcodebuild >/dev/null 2>&1 || die "xcodebuild not found. Install Xcode, then re-run."
xcodebuild -version >/dev/null 2>&1 || die "xcodebuild is present but unusable. Try: sudo xcodebuild -license accept"

if $checks; then
    step "Running the shared core's checks"
    (cd RSReceiptsCore && swift run RSReceiptsCoreChecks)
fi

if $hardware; then
    udid="$(xcrun devicectl list devices 2>/dev/null | awk '
        /\(UDID\)/ && !/simulated/ && /available|connected/ {
            match($0, /[0-9A-F]{8}-[0-9A-F]{16}/)
            if (RSTART) { print substr($0, RSTART, RLENGTH); exit }
        }')"
    [[ -n "$udid" ]] || die "no physical device found. Plug one in and unlock it, then:
    xcrun devicectl list devices"
    name="$(xcrun devicectl list devices 2>/dev/null | grep "$udid" | sed 's/  */ /g' | cut -d' ' -f1-2)"
    step "Target: $name ($udid)"
fi

step "Building $SCHEME"
# Kept quiet unless it fails: xcodebuild writes its destination list to stderr even on success.
build_log="$(mktemp)"
build_args=(-scheme "$SCHEME" -project "$PROJECT" -configuration Debug -derivedDataPath "$DERIVED")
if $hardware; then
    # Automatic signing mints the certificate and profile on first use, which needs an Apple ID
    # in Xcode. DEVELOPMENT_TEAM is only required when the account has more than one team.
    # The exact device, not generic/platform=iOS: with a generic destination Xcode has no
    # device to register with the team, and automatic signing fails with "your team has no
    # devices from which to generate a provisioning profile".
    # -allowProvisioningDeviceRegistration lets xcodebuild add the device to the developer
    # portal itself; without it, a device Xcode has never seen fails with "isn't registered in
    # your developer account" and has to be added by hand.
    build_args+=(-destination "id=$udid" -allowProvisioningUpdates -allowProvisioningDeviceRegistration)
    [[ -n "${DEVELOPMENT_TEAM:-}" ]] && build_args+=("DEVELOPMENT_TEAM=$DEVELOPMENT_TEAM")
else
    build_args+=(-sdk iphonesimulator)
fi
if ! xcodebuild "${build_args[@]}" build >"$build_log" 2>&1; then
    grep -E "error:|warning:" "$build_log" | head -20 >&2 || tail -30 "$build_log" >&2
    if $hardware && grep -q "requires a development team\|No signing certificate\|no Accounts" "$build_log"; then
        echo "" >&2
        echo "Signing is not set up. Add an Apple ID in Xcode > Settings > Accounts (a free" >&2
        echo "one is enough), then re-run. If the account has several teams, pass" >&2
        echo "DEVELOPMENT_TEAM=XXXXXXXXXX." >&2
    fi
    die "build failed. Full log: $build_log"
fi
rm -f "$build_log"

if $hardware; then
    app="$DERIVED/Build/Products/Debug-iphoneos/RS Receipts.app"
else
    app="$DERIVED/Build/Products/Debug-iphonesimulator/RS Receipts.app"
fi
[[ -d "$app" ]] || die "no app bundle at $app"

if $hardware; then
    if $clean; then
        step "Removing the installed app and its data"
        xcrun devicectl device uninstall app --device "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
    fi

    step "Installing"
    xcrun devicectl device install app --device "$udid" "$app" >/dev/null \
        || die "install failed. Is Developer Mode on (Settings > Privacy & Security) and the
    device unlocked? The app also needs iOS 27 or newer, matching the deployment target."

    step "Launching"
    # The `--` matters: devicectl parses a leading-dash argument as its own short-option
    # cluster, so a bare -seedDemoData fails with "Missing value for '-t <seconds>'".
    if $demo; then
        xcrun devicectl device process launch --device "$udid" "$BUNDLE_ID" -- -seedDemoData \
            | sed "s/^/${dim}/;s/$/${off}/"
    else
        xcrun devicectl device process launch --device "$udid" "$BUNDLE_ID" \
            | sed "s/^/${dim}/;s/$/${off}/"
    fi
    step "Done"
    exit 0
fi

udid="$(xcrun simctl list devices available | awk -v want="$device" '
    index($0, want) && match($0, /[0-9A-F-]{36}/) { print substr($0, RSTART, RLENGTH); exit }')"
[[ -n "$udid" ]] || die "no available simulator matching \"$device\". Try: ./run.sh --list"

step "Booting $device"
xcrun simctl boot "$udid" 2>/dev/null || true
xcrun simctl bootstatus "$udid" -b >/dev/null

if $clean; then
    step "Removing the installed app and its data"
    xcrun simctl uninstall "$udid" "$BUNDLE_ID" 2>/dev/null || true
fi

step "Installing"
xcrun simctl terminate "$udid" "$BUNDLE_ID" 2>/dev/null || true
xcrun simctl install "$udid" "$app"

step "Launching"
if $demo; then
    xcrun simctl launch "$udid" "$BUNDLE_ID" -seedDemoData | sed "s/^/${dim}/;s/$/${off}/"
else
    xcrun simctl launch "$udid" "$BUNDLE_ID" | sed "s/^/${dim}/;s/$/${off}/"
fi

# Bring the simulator window up, unless this is a headless screenshot run.
#
# Xcode 27 removed Simulator.app and replaced it with DeviceHub.app, so `open -a Simulator`
# fails with "Unable to find application named 'Simulator'". Neither app is registered with
# LaunchServices by bundle id either, so both are opened by full path.
if [[ -z "$shot" ]]; then
    hub="$(dirname "${DEVELOPER_DIR:-}")/Applications/DeviceHub.app"   # Xcode 26+
    legacy="${DEVELOPER_DIR:-}/Applications/Simulator.app"             # Xcode 25 and earlier
    if [[ -d "$hub" ]]; then
        open -a "$hub" || die "could not open $hub"
    elif [[ -d "$legacy" ]]; then
        open -a "$legacy" --args -CurrentDeviceUDID "$udid" || die "could not open $legacy"
    else
        step "No simulator UI found; the app is running headlessly. Use --shot for a screenshot."
    fi
fi

if [[ -n "$shot" ]]; then
    sleep 4
    xcrun simctl io "$udid" screenshot "$shot" >/dev/null 2>&1
    step "Screenshot: $shot"
fi

step "Done"
