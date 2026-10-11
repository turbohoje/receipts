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
    /**
     * Whether this report participates in shared mode. Local to this device: a report you
     * share is one *you* publish, and the flag says nothing about anyone else.
     */
    @ColumnInfo(defaultValue = "0")
    val isShared: Boolean = false,
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
    /**
     * Last modification, for shared-mode last-write-wins. Backfilled from [createdAt] by the
     * schema-2-to-3 migration: without it an edited receipt would look as old as the day it
     * was made, and a remote copy would win every merge, silently discarding local work.
     */
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0,
)

/**
 * A deleted receipt, remembered so the deletion can propagate.
 *
 * Absence has no timestamp: without this, a device that still holds the row would reintroduce
 * it on every merge. Kept in its own table rather than as a soft-delete flag on [Receipt] so
 * that no existing query — totals, exports, the orphan sweep — has to learn to exclude
 * anything.
 */
@Entity(tableName = "tombstones")
data class Tombstone(
    @PrimaryKey val receiptId: String,
    val reportId: String,
    val deletedAt: Long,
    val deletedBy: String,
)

/**
 * A report plus its aggregates. Totals and counts are always computed from the receipt
 * rows and never stored on the report, so they cannot drift out of sync with reality.
 */
data class ReportSummary(
    val id: String,
    val name: String,
    val createdAt: Long,
    @ColumnInfo(name = "isShared") val isShared: Boolean = false,
    @ColumnInfo(name = "receiptCount") val receiptCount: Int,
    @ColumnInfo(name = "totalMinor") val totalMinor: Long,
    @ColumnInfo(name = "firstDate") val firstDate: Long?,
    @ColumnInfo(name = "lastDate") val lastDate: Long?,
)
