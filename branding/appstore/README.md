# App Store assets

Generated, never photographed. Everything here comes from a **simulator with a fresh install
and `-seedDemoData`** — invented merchants, invented amounts, invented card numbers. No real
receipt, and no device holding real receipts, is involved at any point. That is the same rule
`branding/play/` was made under.

```
iphone-6.9/    1320 × 2868   six screenshots   (required: iPhone 6.9")
ipad-13/       2064 × 2752   five screenshots  (required because the app supports iPad)
listing.md     description, keywords, what's-new, privacy answers
```

## Regenerating them

The receipt pictures are drawn in code by `DemoReceiptImage`, and `DemoData` attaches them to
the seeded receipts — both behind `-seedDemoData`, so an ordinary run can never show them.

Reaching a specific screen needs **temporary scaffolding**, the same throwaway-launch-flag
pattern the rest of the iOS work uses: a `-TEMPscreen <name>` argument read by `RootView` and
`ReportDetailScreen`, switching on `list`, `report`, `receipt`, `crop`, `sync`, `sharedfile`
and `settings`. Add it, capture, then remove it and rebuild — it is not in the shipped source,
and `grep -rn TEMP` over the app should come back empty.

Capture and compose:

```sh
xcrun simctl uninstall  "$SIM" cc.rocketscience.receipts     # clear any earlier seed
xcrun simctl install    "$SIM" "<built>/RS Receipts.app"
xcrun simctl status_bar "$SIM" override --time "9:41" --batteryState charged \
  --batteryLevel 100 --cellularBars 4 --wifiBars 3
xcrun simctl launch     "$SIM" cc.rocketscience.receipts -seedDemoData -TEMPscreen report
xcrun simctl io         "$SIM" screenshot --type png report.png
```

The framing — gradient, headline, subhead, rounded bezel — is a short CoreGraphics program
built with `swiftc`, taking `<in> <out> <width> <height> <headline> <subhead>`. The gradient
runs `#4959C3` to `#2F3C91`, sampled from `branding/play/01-reports.png` so the two stores
match.

## Sizes

Apple takes one set per required size and scales the rest. A 6.9" iPhone set is required;
an iPad 13" set is required too, because the app declares iPhone **and** iPad
(`UIDeviceFamily = 1, 2`). 1320 × 2868 and 2064 × 2752 are the native captures from the
iPhone 18 Pro Max and iPad Pro 13-inch simulators, and both are accepted as-is.

Play is a different shape again — **2:1 or squarer**, which raw phone captures are not. See
`branding/play/`.
