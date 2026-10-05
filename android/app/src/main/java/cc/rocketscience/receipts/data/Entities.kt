package cc.rocketscience.receipts.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "reports")
data class Report(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * Position in the manually ordered reports list, ascending — smallest at the top.
     *
     * Every row migrated from schema 1 holds 0, so the list falls back to `createdAt DESC`
     * and looks exactly as it did before dragging existed.
     *
     * The SQL default is declared here, not just in Kotlin, and it is load-bearing: SQLite
     * cannot `ADD COLUMN ... NOT NULL` without one, so the migration must supply `DEFAULT 0`
     * — and Room compares the live schema against this entity on launch and throws if they
     * disagree. A Kotlin-only default would leave the two out of step.
     */
    @ColumnInfo(defaultValue = "0")
    val sortOrder: Long = 0,
)

@Entity(
    tableName = "receipts",
    foreignKeys = [
        ForeignKey(
            entity = Report::class,
            parentColumns = ["id"],
            childColumns = ["reportId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("reportId")],
)
data class Receipt(
    @PrimaryKey val id: String,
    val reportId: String,
    val description: String,
    /** Minor units (cents). Never a floating point type. */
    val amountMinor: Long,
    /** Epoch millis. */
    val date: Long,
    /** Filename inside the image store, or null until a photo is attached (phase 2). */
    val imageFile: String?,
    val createdAt: Long,
)

/**
 * A report plus its aggregates. Totals and counts are always computed from the receipt
 * rows and never stored on the report, so they cannot drift out of sync with reality.
 */
data class ReportSummary(
    val id: String,
    val name: String,
    val createdAt: Long,
    @ColumnInfo(name = "receiptCount") val receiptCount: Int,
    @ColumnInfo(name = "totalMinor") val totalMinor: Long,
    @ColumnInfo(name = "firstDate") val firstDate: Long?,
    @ColumnInfo(name = "lastDate") val lastDate: Long?,
)
