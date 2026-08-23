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
