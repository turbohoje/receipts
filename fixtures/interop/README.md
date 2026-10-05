# Cross-platform archive fixtures

One backup archive written by each platform, read by the other. These exist because the
promise that a backup written on one phone restores on the other is otherwise untested until
someone tries it with real receipts.

| File | Format | Written by | Read by |
| --- | --- | --- | --- |
| `android-backup-v2.zip` | 2 (current) | `BackupWriter` on the JVM, i.e. `java.util.zip` | the iOS `interopChecks` |
| `ios-backup-v2.zip` | 2 (current) | the Swift `BackupWriter` | the Android `ArchiveInteropTest` |
| `android-backup-v1.zip` | 1 | — kept as-is | both, to prove an older backup still restores |
| `ios-backup-v1.zip` | 1 | — kept as-is | both, to prove an older backup still restores |

The v2 pair describes the same two reports and two receipts, so a failure says which side
changed. The v1 pair is deliberately **not** regenerated: its whole value is being an artefact
of the older format.

The v2 fixtures encode one thing on purpose — the two reports' **manual order contradicts their
creation order**, the older one sorting first. A fixture where the two agreed would still pass
if `sortOrder` were silently dropped in transit.

## What they pin

- Android's `ZipOutputStream` writes deflated entries with a **data descriptor**: the local
  header's CRC and both sizes are zero. A reader that walks local headers sees empty entries,
  so the iOS reader works from the central directory. `android-backup-v1.zip` is what proves it.
- `imageFile` is written as an explicit `null` by both platforms — Swift's synthesized encoder
  would otherwise drop the key.
- Timestamps are epoch **milliseconds**: `1756000000123` survives with its milliseconds intact.
- CRC-32 agrees between `java.util.zip.CRC32` and the Swift implementation, since every entry
  is checksum-verified on extraction.
- **`sortOrder` survives**, and a v1 backup without it still restores, defaulting to 0.

## Regenerating

`ios-backup-v1.zip` is byte-for-byte reproducible — fixed timestamp, deterministic image bytes
— and the iOS checks fail if the committed copy drifts from what the current code writes:

```sh
cd ios/RSReceiptsCore && swift run RSReceiptsCoreChecks --write-fixtures
```

`android-backup-v1.zip` is **not** reproducible: `java.util.zip` stamps each entry with the
current time, and `BackupWriter` does not take a clock. It is regenerated deliberately, and
only needs regenerating if the Android backup format itself changes:

```sh
cd android && ./gradlew :app:testDebugUnitTest \
  --tests '*ArchiveInteropTest*' -Drsreceipts.writeFixture=true
```

**Bumping the format means regenerating both v2 fixtures in the same change**, and leaving the
v1 pair alone. Restore refuses anything newer than it understands, so a one-sided bump locks
the other platform out until it catches up.
