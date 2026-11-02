# Branding and store assets

```
play/            Google Play listing — screenshots, icon, feature graphic, release notes
appstore/        App Store listing — screenshots per device size, listing.md
demo-backup.zip  invented data, for making screenshots
icon.svg         the source the PNG icons come from
```

## Never real receipts

Both listings are built from invented data. The phone and the iPad hold real receipts —
including a vehicle registration with a home address — and none of it may reach a store.

**iOS** has `-seedDemoData`, which seeds five invented reports and draws receipt *pictures* in
code (`DemoReceiptImage`). Screenshots come from a simulator with a fresh install, so no device
holding real data is involved at all.

**Android has no such flag.** The method there is `demo-backup.zip`: restore it through
Settings, capture, then restore your own backup. Back up first — the release build is not
debuggable, so `device-data.sh` cannot read it, and restoring replaces everything.

## demo-backup.zip

The same five reports, seventeen receipts and seven receipt photographs the iOS screenshots
show, in the apps' shared backup format (`schemaVersion` 2, JPEGs STORED, UTF-8 names). Either
platform restores it. Verified readable by `java.util.zip`, which is what Android uses.

Rebuild it with `make_demo_backup.py` — it reads the rows a seeded iOS simulator holds, so
the two stores keep showing the same invented data:

```sh
python3 -I make_demo_backup.py <simulator>/Library/Application\ Support/default.store \
    <simulator>/Documents/images branding/demo-backup.zip
```

## Sizes

- **Play** — screenshots must be **2:1 or squarer**; a raw Pixel 11 capture is 2.24:1 and is
  rejected.
- **App Store** — 1320 × 2868 (iPhone 6.9") and 2064 × 2752 (iPad 13"). Both are required,
  because the app declares iPhone and iPad. See `appstore/README.md`.
