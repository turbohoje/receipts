package cc.rocketscience.receipts.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReportDao {

    @Query(
        """
        SELECT r.id            AS id,
               r.name          AS name,
               r.createdAt     AS createdAt,
               r.isShared      AS isShared,
               COUNT(rc.id)                    AS receiptCount,
               COALESCE(SUM(rc.amountMinor),0) AS totalMinor,
               MIN(rc.date)                    AS firstDate,
               MAX(rc.date)                    AS lastDate
        FROM reports r
        LEFT JOIN receipts rc ON rc.reportId = r.id
        GROUP BY r.id
        ORDER BY r.sortOrder ASC, r.createdAt DESC
        """
    )
    fun observeSummaries(): Flow<List<ReportSummary>>

    @Query(
        """
        SELECT r.id            AS id,
               r.name          AS name,
               r.createdAt     AS createdAt,
               r.isShared      AS isShared,
               COUNT(rc.id)                    AS receiptCount,
               COALESCE(SUM(rc.amountMinor),0) AS totalMinor,
               MIN(rc.date)                    AS firstDate,
               MAX(rc.date)                    AS lastDate
        FROM reports r
        LEFT JOIN receipts rc ON rc.reportId = r.id
        WHERE r.id = :reportId
        GROUP BY r.id
        """
    )
    fun observeSummary(reportId: String): Flow<ReportSummary?>

    @Query("SELECT * FROM reports WHERE id = :reportId")
    suspend fun findById(reportId: String): Report?

    @Upsert
    suspend fun upsert(report: Report)

    @Query("UPDATE reports SET name = :name, updatedAt = :updatedAt WHERE id = :reportId")
    suspend fun rename(reportId: String, name: String, updatedAt: Long)

    @Query("DELETE FROM reports WHERE id = :reportId")
    suspend fun deleteById(reportId: String)

    // ----- backup / restore -----

    @Query("SELECT * FROM reports ORDER BY sortOrder ASC, createdAt DESC")
    suspend fun all(): List<Report>

    /** The topmost position in use, or null when there are no reports yet. */
    @Query("SELECT MIN(sortOrder) FROM reports")
    suspend fun minSortOrder(): Long?

    @Query("UPDATE reports SET sortOrder = :sortOrder WHERE id = :reportId")
    suspend fun setSortOrder(reportId: String, sortOrder: Long)

    @Query("UPDATE reports SET isShared = :shared WHERE id = :reportId")
    suspend fun setShared(reportId: String, shared: Boolean)

    /** Cascades to receipts. Used only by restore, which replaces everything. */
    @Query("DELETE FROM reports")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(reports: List<Report>)
}

@Dao
interface ReceiptDao {

    /** Ordered by date, then insertion order, per the spec. */
    @Query(
        """
        SELECT * FROM receipts
        WHERE reportId = :reportId
        ORDER BY date ASC, createdAt ASC
        """
    )
    fun observeForReport(reportId: String): Flow<List<Receipt>>

    @Query("SELECT * FROM receipts WHERE reportId = :reportId ORDER BY date ASC, createdAt ASC")
    suspend fun listForReport(reportId: String): List<Receipt>

    @Query("SELECT * FROM receipts WHERE id = :receiptId")
    fun observeById(receiptId: String): Flow<Receipt?>

    @Query("SELECT * FROM receipts WHERE id = :receiptId")
    suspend fun findById(receiptId: String): Receipt?

    /** Every image filename still referenced by a row — the orphan sweep's allow-list. */
    @Query("SELECT imageFile FROM receipts WHERE imageFile IS NOT NULL")
    suspend fun allImageFiles(): List<String>

    @Upsert
    suspend fun upsert(receipt: Receipt)

    @Query("DELETE FROM receipts WHERE id = :receiptId")
    suspend fun deleteById(receiptId: String)

    @Query("SELECT * FROM receipts ORDER BY date, createdAt")
    suspend fun all(): List<Receipt>

    /**
     * Repairs rows written before receipts carried a modification time.
     *
     * A zero here means 1970, so the row loses every merge it is ever in — a collaborator's
     * copy always looks newer. `createdAt` is the honest answer for a row that has not been
     * edited since it was made.
     */
    @Query("UPDATE receipts SET updatedAt = createdAt WHERE updatedAt = 0")
    suspend fun backfillUpdatedAt(): Int

    @Insert
    suspend fun insertAll(receipts: List<Receipt>)
}

/**
 * Deleted receipts, kept so the deletion survives a merge.
 *
 * Separate from [ReceiptDao] on purpose: nothing that reads receipts needs to know this table
 * exists, so totals, exports and the orphan sweep are untouched by shared mode.
 */
@Dao
interface TombstoneDao {

    @Query("SELECT * FROM tombstones WHERE reportId = :reportId")
    suspend fun forReport(reportId: String): List<Tombstone>

    @Query("SELECT * FROM tombstones")
    suspend fun all(): List<Tombstone>

    @Upsert
    suspend fun upsert(tombstone: Tombstone)

    @Upsert
    suspend fun upsertAll(tombstones: List<Tombstone>)

    /** Dropped when a receipt with the same id legitimately comes back. */
    @Query("DELETE FROM tombstones WHERE receiptId = :receiptId")
    suspend fun clear(receiptId: String)

    /** Used by restore, which replaces every row and so invalidates every tombstone. */
    @Query("DELETE FROM tombstones")
    suspend fun clearAll()
}
