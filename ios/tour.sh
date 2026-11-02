#!/usr/bin/env bash
#
# Film the app being used, for the screen recording App Review asks for.
#
#   ./tour.sh --device              on the attached iPhone or iPad  (what Apple wants)
#   ./tour.sh --device "Mini Plane" name it when more than one is attached
#   ./tour.sh                       on a simulator
#   ./tour.sh --sim "iPhone 17"     a particular simulator
#   ./tour.sh --only 1              just the first-run segment
#   ./tour.sh --keep-build          reuse the last build-for-testing
#
# Nothing can tap a phone from a shell, so the tapping is a UI test —
# RSReceiptsUITests/AppTourUITests — and this films it. The test is paced for video, not
# for speed. Output lands in branding/appstore/review/; only the joined file is committed.
#
# The two paths film differently, because they have to:
#
#   simulator  `simctl io recordVideo` runs alongside the test. There is no such command for
#              a physical device.
#   device     XCTest's own automatic screen recording, kept by `systemAttachmentLifetime`
#              in the scheme and pulled out of the result bundle afterwards. This is the
#              only way to film real hardware unattended.
#
# A device run needs three things set on the device, none of them scriptable:
#   1. Developer Mode on             Settings > Privacy & Security
#   2. Enable UI Automation on       Settings > Developer
#      Without it the runner dies with "Timed out while enabling automation mode."
#   3. unlocked, with Auto-Lock set to Never — Settings > Display & Brightness.
#      This one is not cosmetic. XCUITest's synthetic taps do not count as user activity,
#      so the device sleeps mid-run and every interaction then waits out a timeout: the
#      same segment took 116s awake and 1728s asleep, and the recording is just as long.
# It also wipes the app before each segment, so back up anything real on the device first:
#   xcrun devicectl device copy from --device <udid> --domain-type appDataContainer \
#     --domain-identifier cc.rocketscience.receipts --source Documents --destination ./out

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

if [[ -z "${DEVELOPER_DIR:-}" && -d /Applications/Xcode.app ]]; then
    export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
fi

BUNDLE_ID="cc.rocketscience.receipts"
SCHEME="RSReceipts"
PROJECT="RSReceipts/RSReceipts.xcodeproj"
SUITE="RSReceiptsUITests/AppTourUITests"
OUT="../branding/appstore/review"
TEAM="${DEVELOPMENT_TEAM:-R23W8J48BW}"

sim_name="iPhone 18 Pro"
device_match=""
on_device=false
only=""
keep_build=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --sim)    sim_name="$2"; on_device=false; shift ;;
        --device)
            on_device=true
            case "${2:-}" in ""|--*) ;; *) device_match="$2"; shift ;; esac
            ;;
        --only)       only="$2"; shift ;;
        --keep-build) keep_build=true ;;
        -h|--help)    awk 'NR>1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
        *)            echo "unknown option: $1  (try --help)" >&2; exit 2 ;;
    esac
    shift
done

if [[ -t 1 ]]; then
    bold=$'\033[1m'; red=$'\033[31m'; green=$'\033[32m'; off=$'\033[0m'
else
    bold=''; red=''; green=''; off=''
fi
step() { printf '%s==>%s %s%s%s\n' "$green" "$off" "$bold" "$*" "$off"; }
die()  { printf '%s==>%s %s\n' "$red" "$off" "$*" >&2; exit 1; }

mkdir -p "$OUT"

# Cut ranges for the current segment, in seconds from the start of the trimmed recording.
CUTS=()

# --------------------------------------------------------------------------------------
# Encoding
#
# Two passes, on purpose. Both capture paths write a *variable* frame rate — through a
# static stretch they emit almost nothing — and cutting a VFR stream into pieces and
# concatenating them yields nonsense timestamps: one attempt came out as a 168 KB file
# claiming three minutes, and the joined pair claimed twenty-two. The first pass normalises
# to a constant 30fps at a size a reviewer can watch; any cutting happens to that.
# --------------------------------------------------------------------------------------
encode() {
    local source="$1" name="$2" trim="$3" length="$4"
    local target="$OUT/$name.mp4" first="$OUT/$name.mp4"
    (( ${#CUTS[@]} > 0 )) && first="$OUT/.$name-cfr.mp4"

    # Fit inside 1280 either way round: these recordings are portrait from a phone and
    # landscape from an iPad.
    local fit="fps=30,scale=1280:1280:force_original_aspect_ratio=decrease:force_divisible_by=2"

    local args=(-nostdin -y -ss "$trim")
    [[ -n "$length" ]] && args+=(-t "$length")
    args+=(-i "$source" -vf "$fit" -c:v libx264 -preset slow -crf 26 -pix_fmt yuv420p
           -movflags +faststart -an "$first")
    ffmpeg "${args[@]}" >/dev/null 2>&1 || die "ffmpeg could not re-encode $source"

    if (( ${#CUTS[@]} > 0 )); then
        local graph="" parts="" pieces=0 keep_from=0 a b
        for cut in "${CUTS[@]}"; do
            read -r a b <<<"$cut"
            (( a < keep_from )) && continue
            (( b <= a )) && continue
            graph+="[0:v]trim=${keep_from}:${a},setpts=PTS-STARTPTS[p${pieces}];"
            parts+="[p${pieces}]"
            pieces=$(( pieces + 1 ))
            keep_from=$b
        done
        graph+="[0:v]trim=start=${keep_from},setpts=PTS-STARTPTS[p${pieces}];"
        parts+="[p${pieces}]"
        graph+="${parts}concat=n=$(( pieces + 1 )):v=1[v]"
        ffmpeg -nostdin -y -i "$first" -filter_complex "$graph" -map "[v]" \
            -c:v libx264 -preset slow -crf 26 -pix_fmt yuv420p \
            -movflags +faststart -an "$target" >/dev/null 2>&1 \
            || die "ffmpeg could not cut the dead stretches out of $first"
        rm -f "$first"
    fi
    printf '    %s (%s, %ss, %s dead stretches cut)\n' \
        "$target" "$(du -h "$target" | cut -f1)" \
        "$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$target" | cut -d. -f1)" \
        "${#CUTS[@]}"
}

duration_of() { ffprobe -v error -show_entries format=duration -of csv=p=0 "$1"; }

# --------------------------------------------------------------------------------------
# Simulator
# --------------------------------------------------------------------------------------
film_simulator() {
    local name="$1" test="$2"
    local movie="$OUT/$name.mov"
    local log; log="$(mktemp)"

    step "Filming $name on $sim_name"
    xcrun simctl terminate "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
    xcrun simctl uninstall "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true

    rm -f "$movie"
    xcrun simctl io "$udid" recordVideo --codec h264 --force "$movie" >/dev/null 2>&1 &
    local recorder=$!
    local started; started="$(date +%s)"
    sleep 2

    local status=0
    xcodebuild -scheme "$SCHEME" -project "$PROJECT" -configuration Debug \
        -derivedDataPath "$DERIVED" -sdk iphonesimulator \
        -destination "id=$udid" -only-testing:"$SUITE/$test" \
        test-without-building >"$log" 2>&1 || status=$?

    # recordVideo writes its moov atom on SIGINT; anything harsher leaves no playable file.
    sleep 1
    kill -INT "$recorder" 2>/dev/null || true
    wait "$recorder" 2>/dev/null || true
    report "$log" "$status"
    [[ -s "$movie" ]] || die "no video was written to $movie"

    # The camera rolls through the runner installing the app and again through its teardown.
    # The test logs when it launched and when it finished; keep a few seconds either side.
    local launched finished trim length
    launched="$(marker "$log" TOUR-LAUNCHED)"
    finished="$(marker "$log" TOUR-FINISHED)"
    trim=0
    if [[ -n "$launched" ]]; then
        trim=$(( launched - started - 4 ))
        (( trim < 0 )) && trim=0
    fi
    length=""
    [[ -n "$finished" ]] && length=$(( finished - started - trim + 2 ))

    CUTS=()
    while read -r a b; do
        [[ "$a" =~ ^[0-9]+$ && "$b" =~ ^[0-9]+$ ]] || continue
        CUTS+=("$(( a - started - trim )) $(( b - started - trim ))")
    done < <(sed -n 's/^TOUR-CUT: \([0-9][0-9]*\) \([0-9][0-9]*\).*/\1 \2/p' "$log")

    encode "$movie" "$name" "$trim" "$length"
    (( status == 0 )) && rm -f "$log"
    return 0
}

# --------------------------------------------------------------------------------------
# Physical device
# --------------------------------------------------------------------------------------
film_device() {
    local name="$1" test="$2"
    local bundle="$DERIVED/$name.xcresult"
    local att="$DERIVED/$name-attachments"
    local log; log="$(mktemp)"

    step "Filming $name on $device_name"
    xcrun devicectl device uninstall app --device "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
    rm -rf "$bundle" "$att"

    local status=0
    xcodebuild -scheme "$SCHEME" -project "$PROJECT" -configuration Debug \
        -derivedDataPath "$DERIVED" -destination "id=$udid" \
        -allowProvisioningUpdates DEVELOPMENT_TEAM="$TEAM" \
        -only-testing:"$SUITE/$test" -resultBundlePath "$bundle" \
        test >"$log" 2>&1 || status=$?
    report "$log" "$status"

    mkdir -p "$att"
    xcrun xcresulttool export attachments --path "$bundle" --output-path "$att" \
        >/dev/null 2>&1 || die "could not read $bundle"
    local movie; movie="$(ls -S "$att"/*.mp4 2>/dev/null | head -1)" || true
    [[ -n "$movie" ]] || die "no screen recording in the result bundle. Is 'Enable UI
    Automation' on in Settings > Developer, and does the scheme still carry
    systemAttachmentLifetime = keepAlways?"

    # XCTest starts recording a moment after the test does, so the two clocks need lining
    # up before the test's own markers mean anything. The end of the recording is the end of
    # the test, which gives the offset exactly.
    local launched finished duration offset trim
    launched="$(marker "$log" TOUR-LAUNCHED-REL)"
    finished="$(marker "$log" TOUR-FINISHED-REL)"
    duration="$(duration_of "$movie")"
    offset=0
    [[ -n "$finished" ]] && offset="$(awk -v f="$finished" -v d="$duration" \
        'BEGIN { o = f - d; printf "%d", (o > 0 ? o + 0.5 : 0) }')"
    trim=0
    if [[ -n "$launched" ]]; then
        trim=$(( launched - offset - 4 ))
        (( trim < 0 )) && trim=0
    fi

    # sed captures the two numbers rather than the rest of the line: xcodebuild output can
    # carry a trailing carriage return, and `$(( 145<CR> ))` is an arithmetic syntax error.
    CUTS=()
    while read -r a b; do
        [[ "$a" =~ ^[0-9]+$ && "$b" =~ ^[0-9]+$ ]] || continue
        CUTS+=("$(( a - offset - trim )) $(( b - offset - trim ))")
    done < <(sed -n 's/^TOUR-CUT-REL: \([0-9][0-9]*\) \([0-9][0-9]*\).*/\1 \2/p' "$log")

    encode "$movie" "$name" "$trim" ""
    (( status == 0 )) && rm -f "$log"
    return 0
}

marker() { sed -n "s/^$2: \([0-9]*\).*/\1/p" "$1" | head -1; }

report() {
    local log="$1" status="$2"
    grep -E "^TOUR:|Test Case .* (failed|passed)" "$log" | sed 's/^/    /' || true
    if (( status != 0 )); then
        printf '%s==>%s the test reported a failure; the recording is still usable. Log: %s\n' \
            "$red" "$off" "$log" >&2
    fi
}

# --------------------------------------------------------------------------------------

if $on_device; then
    DERIVED="${TMPDIR:-/tmp}/rsreceipts-dd-device"
    attached=()
    while IFS= read -r line; do attached+=("$line"); done < <(
        xcrun devicectl list devices 2>/dev/null | awk '
        /\(UDID\)/ && !/simulated/ && /available|connected/ {
            match($0, /[0-9A-F]{8}-[0-9A-F]{16}/)
            if (!RSTART) next
            id = substr($0, RSTART, RLENGTH)
            name = $0; sub(/[ ]{2,}.*/, "", name)
            print id "\t" name
        }')
    [[ ${#attached[@]} -gt 0 ]] || die "no physical device found. Plug one in and unlock it."
    if [[ -n "$device_match" ]]; then
        match="$(printf '%s\n' "${attached[@]}" | grep -i -- "$device_match" | head -1)"
        [[ -n "$match" ]] || die "no attached device matching \"$device_match\""
    elif [[ ${#attached[@]} -gt 1 ]]; then
        die "more than one device is attached; name the one you mean:
$(printf '%s\n' "${attached[@]}" | cut -f2 | sed 's|^|  ./tour.sh --device "|; s|$|"|')"
    else
        match="${attached[0]}"
    fi
    udid="${match%%$'\t'*}"
    device_name="${match#*$'\t'}"
    step "Target: $device_name ($udid)"
else
    DERIVED="${TMPDIR:-/tmp}/rsreceipts-dd"
    udid="$(xcrun simctl list devices available | awk -v want="$sim_name" '
        index($0, want) && match($0, /[0-9A-F-]{36}/) { print substr($0, RSTART, RLENGTH); exit }')"
    [[ -n "$udid" ]] || die "no available simulator matching \"$sim_name\""

    step "Booting $sim_name"
    xcrun simctl boot "$udid" 2>/dev/null || true
    xcrun simctl bootstatus "$udid" -b >/dev/null
    # A believable status bar; cosmetic, and not worth failing over.
    xcrun simctl status_bar "$udid" override \
        --time "9:41" --batteryState charged --batteryLevel 100 \
        --cellularMode active --cellularBars 4 --wifiMode active --wifiBars 3 \
        >/dev/null 2>&1 || true

    if ! $keep_build; then
        step "Building for testing"
        log="$(mktemp)"
        if ! xcodebuild -scheme "$SCHEME" -project "$PROJECT" -configuration Debug \
            -derivedDataPath "$DERIVED" -sdk iphonesimulator \
            -destination "id=$udid" build-for-testing >"$log" 2>&1; then
            grep -E "error:" "$log" | head -20 >&2 || tail -40 "$log" >&2
            die "build failed. Full log: $log"
        fi
        rm -f "$log"
    fi
fi

# Plain ifs, not `[[ ... ]] && film`: under `set -e` a false test is a non-zero command and
# would end the script before the other segment ran.
film() { if $on_device; then film_device "$@"; else film_simulator "$@"; fi; }
if [[ -z "$only" || "$only" == 1 ]]; then film "01-first-run" "test1FirstRun"; fi
if [[ -z "$only" || "$only" == 2 ]]; then film "02-full-tour" "test2FullTour"; fi

# One file to attach, with the first run first: that is the order a reviewer meets the app
# in. Both segments are already h264 at the same size, so this is a stream copy.
if [[ -z "$only" ]] && command -v ffmpeg >/dev/null 2>&1; then
    step "Joining both segments"
    list="$(mktemp)"
    printf "file '%s'\n" "$PWD/$OUT/01-first-run.mp4" "$PWD/$OUT/02-full-tour.mp4" >"$list"
    ffmpeg -nostdin -y -f concat -safe 0 -i "$list" -c copy -movflags +faststart \
        "$OUT/rs-receipts-review-tour.mp4" >/dev/null 2>&1 \
        || die "ffmpeg could not join the segments"
    rm -f "$list"
    printf '    %s (%s, %ss)\n' "$OUT/rs-receipts-review-tour.mp4" \
        "$(du -h "$OUT/rs-receipts-review-tour.mp4" | cut -f1)" \
        "$(duration_of "$OUT/rs-receipts-review-tour.mp4" | cut -d. -f1)"
fi

$on_device || xcrun simctl status_bar "$udid" clear >/dev/null 2>&1 || true
step "Done"
