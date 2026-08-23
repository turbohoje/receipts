package cc.rocketscience.receipts.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Currency
import java.util.zip.ZipInputStream

class ZipExporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val usd = Currency.getInstance("USD")
    private val utc = ZoneId.of("UTC")
    private val exporter = ZipExporter(utc)

    private fun day(y: Int, m: Int, d: Int) =
        LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun image(name: String): File =
        temp.newFile(name).apply { writeText("jpeg-bytes-$name") }

    private fun rows() = listOf(
        ExportRow(1, day(2026, 8, 21), "Taxi to airport", 2450, image("a.jpg")),
        ExportRow(2, day(2026, 8, 22), "Dinner, drinks", 8100, image("b.jpg")),
        ExportRow(3, day(2026, 8, 23), "Cash tip", 500, null),
    )

    @Test
    fun `csv has a header, a row each and a total`() {
        val csv = exporter.buildCsv(rows(), usd)
        val lines = csv.trim().split("\r\n")

        assertEquals("date,description,amount,currency,image", lines.first())
        assertEquals(5, lines.size) // header + 3 rows + total
        assertTrue(lines[1].startsWith("2026-08-21,Taxi to airport,24.50,USD,"))
        // 24.50 + 81.00 + 5.00
        assertEquals(",TOTAL,110.50,USD,", lines.last())
    }

    @Test
    fun `a description containing a comma stays one column`() {
        val csv = exporter.buildCsv(rows(), usd)
        val dinner = csv.split("\r\n").first { it.contains("Dinner") }
        assertTrue(dinner, dinner.contains("\"Dinner, drinks\""))
    }

    @Test
    fun `a receipt with no image gets an empty image column`() {
        val csv = exporter.buildCsv(rows(), usd)
        val tip = csv.split("\r\n").first { it.contains("Cash tip") }
        assertTrue(tip, tip.endsWith("USD,"))
    }

    @Test
    fun `image names are numbered to match the csv order`() {
        val names = rows().filter { it.image != null }.map { exporter.imageEntryName(it) }
        assertEquals(listOf("images/01-taxi-to-airport.jpg", "images/02-dinner-drinks.jpg"), names)
    }

    @Test
    fun `zip contains the csv and only the images that exist`() {
        val out = ByteArrayOutputStream()
        exporter.write(out, rows(), usd)

        val entries = mutableListOf<String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                entries += e.name
                zip.closeEntry()
            }
        }
        assertEquals(
            listOf("report.csv", "images/01-taxi-to-airport.jpg", "images/02-dinner-drinks.jpg"),
            entries,
        )
    }

    @Test
    fun `a referenced image missing from disk is skipped rather than failing the export`() {
        val vanished = image("gone.jpg")
        vanished.delete()
        val out = ByteArrayOutputStream()
        exporter.write(out, listOf(ExportRow(1, day(2026, 8, 21), "Taxi", 2450, vanished)), usd)

        val entries = mutableListOf<String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                entries += e.name
                zip.closeEntry()
            }
        }
        // The line item still appears in the CSV; only the image is absent.
        assertEquals(listOf("report.csv"), entries)
    }

    @Test
    fun `zipped image bytes match the source`() {
        val row = ExportRow(1, day(2026, 8, 21), "Taxi", 2450, image("c.jpg"))
        val out = ByteArrayOutputStream()
        exporter.write(out, listOf(row), usd)

        var found: String? = null
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name.startsWith("images/")) found = zip.readBytes().decodeToString()
                zip.closeEntry()
            }
        }
        assertEquals("jpeg-bytes-c.jpg", found)
    }

    @Test
    fun `an empty report still yields a csv with a zero total`() {
        val csv = exporter.buildCsv(emptyList(), usd)
        assertEquals(",TOTAL,0.00,USD,", csv.trim().split("\r\n").last())
    }
}
