package cc.rocketscience.receipts.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Report::class, Receipt::class, Tombstone::class],
    version = 3,
    exportSchema = true,
)
abstract class ReceiptsDatabase : RoomDatabase() {

    abstract fun reportDao(): ReportDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun tombstoneDao(): TombstoneDao

    companion object {

        /**
         * Adds `reports.sortOrder` for the manually ordered reports list.
         *
         * Everything existing defaults to 0, which is deliberate: the list query orders by
         * `sortOrder ASC, createdAt DESC`, so a database full of zeroes sorts exactly as it
         * did before this column existed. Nothing moves until something is dragged.
         *
         * There is no destructive fallback on this builder, and the phone holds real
         * receipts — so a missing migration is a crash on launch, not a reset.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE reports ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Adds shared mode: `reports.isShared`, `receipts.updatedAt`, and the tombstones
         * table.
         *
         * `updatedAt` is backfilled from `createdAt` rather than left at 0. A receipt that has
         * never been edited since it was made is exactly what `createdAt` describes, and
         * leaving zeroes would make every existing receipt lose every merge.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reports ADD COLUMN isShared INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE receipts ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE receipts SET updatedAt = createdAt")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS tombstones (" +
                        "receiptId TEXT NOT NULL, " +
                        "reportId TEXT NOT NULL, " +
                        "deletedAt INTEGER NOT NULL, " +
                        "deletedBy TEXT NOT NULL, " +
                        "PRIMARY KEY(receiptId))"
                )
            }
        }

        fun build(context: Context): ReceiptsDatabase =
            Room.databaseBuilder(context, ReceiptsDatabase::class.java, "receipts.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
