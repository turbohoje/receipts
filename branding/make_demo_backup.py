"""Builds a demo backup in the app's own format, for making store screenshots.

Reads the rows the iOS simulator seeded, so the Play captures show exactly the same invented
data as the App Store ones. Writes the archive the way both apps do: text deflated, JPEGs
STORED (deflating a JPEG grows it), names UTF-8 with bit 11 set.
"""
import json, sqlite3, zipfile, pathlib, sys, time

store, images_dir, out = sys.argv[1], pathlib.Path(sys.argv[2]), sys.argv[3]
con = sqlite3.connect(store)
con.execute("PRAGMA journal_mode=WAL")

reports, receipts, used = [], [], set()
rows = con.execute(
    "SELECT Z_PK, ZID, ZNAME, ZCREATEDAT, ZUPDATEDAT, ZSORTORDER FROM ZSTOREDREPORT "
    "ORDER BY ZCREATEDAT DESC").fetchall()
# A new report takes the top, so laying them out newest-first and numbering from zero
# reproduces the order the app itself would hold.
for order, (pk, rid, name, created, updated, _) in enumerate(rows):
    reports.append({"id": rid, "name": name, "createdAt": created,
                    "updatedAt": updated or created, "sortOrder": order})
    for cid, desc, amount, date, image, rcreated in con.execute(
        "SELECT ZID, ZRECEIPTDESCRIPTION, ZAMOUNTMINOR, ZDATE, ZIMAGEFILE, ZCREATEDAT "
        "FROM ZSTOREDRECEIPT WHERE ZREPORT = ? ORDER BY ZCREATEDAT", (pk,)):
        receipts.append({"id": cid, "reportId": rid, "description": desc,
                         "amountMinor": amount, "date": date,
                         "imageFile": image, "createdAt": rcreated})
        if image:
            used.add(image)

manifest = {"schemaVersion": 2, "appVersion": "0.2.1-demo",
            "createdAt": int(time.time() * 1000), "currency": "USD",
            "reports": reports, "receipts": receipts}

with zipfile.ZipFile(out, "w") as z:
    z.writestr(zipfile.ZipInfo("manifest.json"),
               json.dumps(manifest, ensure_ascii=False, indent=2),
               compress_type=zipfile.ZIP_DEFLATED)
    for name in sorted(used):
        src = images_dir / name
        if not src.exists():
            print(f"  missing image {name}", file=sys.stderr); continue
        info = zipfile.ZipInfo(f"images/{name}")
        info.compress_type = zipfile.ZIP_STORED      # a deflated JPEG gets bigger
        z.writestr(info, src.read_bytes())

print(f"{len(reports)} reports, {len(receipts)} receipts, {len(used)} images -> {out}")
