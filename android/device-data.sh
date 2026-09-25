#!/usr/bin/env bash
#
# Pull the app's data off the device and put it back. A stopgap until phase 4 ships real
# in-app backup, and the safety net for switching between debug and release builds.
#
#   ./device-data.sh backup [dir]     copy database + images to dir (default ./device-backups/<stamp>)
#   ./device-data.sh restore <dir>    put a backup back onto the device
#   ./device-data.sh verify <dir>     re-check a backup's checksums without touching the device
#
# Requires a DEBUGGABLE build installed (adb run-as only works on one), so this cannot read
# or write a release build's data.

set -euo pipefail
# Resolve this script's own path before cd, so --help can still read its header comment
# when the script is invoked by path from another directory.
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
cd "$(dirname "${BASH_SOURCE[0]}")"

PACKAGE="cc.rocketscience.receipts"
DATA="/data/data/$PACKAGE"
DB_FILES=(receipts.db receipts.db-wal receipts.db-shm)

if [[ -t 1 ]]; then
    bold=$'\033[1m'; red=$'\033[31m'; green=$'\033[32m'; yellow=$'\033[33m'; dim=$'\033[2m'; off=$'\033[0m'
else
    bold=''; red=''; green=''; yellow=''; dim=''; off=''
fi
step() { printf '%s==>%s %s%s%s\n' "$green" "$off" "$bold" "$*" "$off"; }
warn() { printf '%s==>%s %s\n' "$yellow" "$off" "$*" >&2; }
die()  { printf '%s==>%s %s\n' "$red" "$off" "$*" >&2; exit 1; }

find_adb() {
    if [[ -n "${ADB:-}" && -x "${ADB}" ]]; then echo "$ADB"; return; fi
    for c in "${ANDROID_HOME:-}/platform-tools/adb" "$HOME/Library/Android/sdk/platform-tools/adb"; do
        [[ -n "$c" && -x "$c" ]] && { echo "$c"; return; }
    done
    command -v adb 2>/dev/null && return
    die "adb not found"
}
ADB_BIN="$(find_adb)"
adb() { "$ADB_BIN" "$@"; }

require_debuggable() {
    local state
    state="$(adb devices | awk 'NR>1 && NF>=2 {print $2; exit}')"
    [[ "$state" == "device" ]] || die "No usable device (state: ${state:-none}). Unlock it and allow USB debugging."
    if ! adb shell "run-as $PACKAGE true" >/dev/null 2>&1; then
        die "run-as failed. Either $PACKAGE is not installed, or the installed build is a
    release build, whose data adb cannot read. Install the debug build first:
        ./deploy.sh --no-launch"
    fi
}

sha() { shasum -a 256 "$1" | awk '{print $1}'; }

# ---------------------------------------------------------------- backup

do_backup() {
    local dir="${1:-device-backups/$(date +%Y%m%d-%H%M%S)}"
    require_debuggable
    mkdir -p "$dir/images"

    step "Stopping the app so nothing is mid-write"
    adb shell am force-stop "$PACKAGE"

    step "Copying the database"
    local got_db=false
    for f in "${DB_FILES[@]}"; do
        if adb shell "run-as $PACKAGE test -f $DATA/databases/$f" 2>/dev/null; then
            adb exec-out run-as "$PACKAGE" cat "$DATA/databases/$f" > "$dir/$f"
            got_db=true
        fi
    done
    $got_db || die "no database found on the device; nothing to back up"

    step "Copying images"
    local count=0
    # `ls` on an empty or absent dir must not abort the run.
    for name in $(adb shell "run-as $PACKAGE ls $DATA/files/images 2>/dev/null" | tr -d '\r'); do
        [[ -z "$name" ]] && continue
        adb exec-out run-as "$PACKAGE" cat "$DATA/files/images/$name" > "$dir/images/$name"
        count=$((count + 1))
    done

    # Fold the write-ahead log into the database itself, so the backup is one self-contained
    # file. Any tool that opens a WAL-mode database does this anyway when it closes cleanly —
    # which silently rewrites receipts.db and deletes the -wal/-shm beside it. Doing it here,
    # deliberately and before checksumming, is what stops the manifest describing a state the
    # backup is no longer in.
    step "Consolidating the write-ahead log"
    local sq="$HOME/Library/Android/sdk/platform-tools/sqlite3"
    if [[ -x "$sq" ]]; then
        "$sq" "$dir/receipts.db" "PRAGMA wal_checkpoint(TRUNCATE);" >/dev/null
        rm -f "$dir/receipts.db-wal" "$dir/receipts.db-shm"
    else
        warn "sqlite3 not found; keeping -wal/-shm alongside the database"
    fi

    summarise "$dir" "$count"

    # Checksums last: nothing may touch these files afterwards.
    step "Writing checksums"
    (cd "$dir" && find . -type f ! -name MANIFEST.sha256 | sort | while read -r f; do
        printf '%s  %s\n' "$(shasum -a 256 "$f" | awk '{print $1}')" "${f#./}"
    done) > "$dir/MANIFEST.sha256"

    do_verify "$dir"
    step "Backed up to ${bold}$dir${off}"
    printf '%s\n' "${dim}Restore with: ./device-data.sh restore $dir${off}"
}

summarise() {
    local dir="$1" images="$2"
    local sq="$HOME/Library/Android/sdk/platform-tools/sqlite3"
    printf '\n%sContents%s\n' "$bold" "$off"
    if [[ -x "$sq" && -f "$dir/receipts.db" ]]; then
        "$sq" -readonly "$dir/receipts.db" \
            "SELECT r.name || '  |  ' || COUNT(c.id) || ' receipt(s)  |  ' ||
                    printf('%.2f', COALESCE(SUM(c.amountMinor),0)/100.0)
             FROM reports r LEFT JOIN receipts c ON c.reportId = r.id
             GROUP BY r.id ORDER BY r.createdAt;" 2>/dev/null | sed 's/^/  /'
    fi
    printf '  %s image file(s)\n\n' "$images"
}

# ---------------------------------------------------------------- verify

do_verify() {
    local dir="${1:?usage: ./device-data.sh verify <dir>}"
    [[ -f "$dir/MANIFEST.sha256" ]] || die "$dir has no MANIFEST.sha256"
    step "Verifying $dir"
    (cd "$dir" && shasum -a 256 --check --status MANIFEST.sha256) \
        && step "All checksums match" \
        || die "CHECKSUM MISMATCH — this backup is not trustworthy"
}

# ---------------------------------------------------------------- restore

do_restore() {
    local dir="${1:?usage: ./device-data.sh restore <dir>}"
    [[ -d "$dir" ]] || die "no such backup: $dir"
    do_verify "$dir"
    require_debuggable

    local staging="/data/local/tmp/receipts-restore"

    step "Stopping the app"
    adb shell am force-stop "$PACKAGE"

    step "Clearing current data"
    # The whole point is to land exactly what was backed up, so stale WAL segments or images
    # from the current install must not survive alongside it.
    #
    # Both directories are created here rather than assumed: on a freshly installed build
    # neither exists yet, because Room only creates databases/ when it first opens the
    # database and the image store only creates images/ on first use.
    adb shell "run-as $PACKAGE sh -c 'rm -rf $DATA/databases && mkdir -p $DATA/databases'"
    adb shell "run-as $PACKAGE sh -c 'rm -rf $DATA/files/images && mkdir -p $DATA/files/images'"

    step "Staging files on the device"
    adb shell "rm -rf $staging && mkdir -p $staging/images"
    for f in "${DB_FILES[@]}"; do
        [[ -f "$dir/$f" ]] && adb push -q "$dir/$f" "$staging/$f" >/dev/null 2>&1 || true
    done
    if compgen -G "$dir/images/*" >/dev/null; then
        adb push -q "$dir"/images/. "$staging/images/" >/dev/null 2>&1 || \
            adb push "$dir"/images/. "$staging/images/" >/dev/null
    fi

    step "Copying into the app's private storage"
    # run-as writes as the app's uid, which is what makes the files usable by the app.
    for f in "${DB_FILES[@]}"; do
        [[ -f "$dir/$f" ]] || continue
        adb shell "run-as $PACKAGE sh -c 'cat $staging/$f > $DATA/databases/$f'"
    done
    for path in "$dir"/images/*; do
        [[ -e "$path" ]] || continue
        local name; name="$(basename "$path")"
        adb shell "run-as $PACKAGE sh -c 'cat $staging/images/$name > $DATA/files/images/$name'"
    done

    adb shell "rm -rf $staging"

    step "Confirming what landed"
    local reports images
    reports=$(adb shell "run-as $PACKAGE test -f $DATA/databases/receipts.db && echo yes" | tr -d '\r')
    images=$(adb shell "run-as $PACKAGE ls $DATA/files/images 2>/dev/null | wc -l" | tr -d '\r')
    [[ "$reports" == "yes" ]] || die "the database is not there after restore"
    printf '  database restored, %s image file(s)\n' "$images"
    step "Restored from $dir"
}

case "${1:-}" in
    backup)  shift; do_backup "${1:-}" ;;
    restore) shift; do_restore "${1:-}" ;;
    verify)  shift; do_verify "${1:-}" ;;
    *) awk 'NR>1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "$SELF"; exit 2 ;;
esac
