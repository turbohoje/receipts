# Assets

`AppIcon` is the same mark the Android launcher icon uses: a white receipt on `#3949AB`.

`branding/icon.svg` at the repo root is the source of truth for both platforms.
`branding/rs-receipts-icon-1024.png` is the rendered 1024×1024 copy, and the file here is a
copy of it — asset catalogs have to contain their images, they cannot reference a path outside.
Re-render from the SVG and copy here if the mark ever changes.

Two things iOS requires that Android does not:

- **No alpha channel.** App Store Connect rejects an icon with one. The committed PNG has none;
  `sips -g hasAlpha` is the check.
- **Full bleed, square, undecorated.** iOS applies its own rounded-rect mask, so the artwork
  must not be pre-rounded and must not carry its own padding. Android's adaptive icon is the
  opposite: it splits background and foreground and crops roughly a third of the foreground to
  the mask, which is why `ic_launcher_foreground.xml` has generous padding the iOS copy must not.
