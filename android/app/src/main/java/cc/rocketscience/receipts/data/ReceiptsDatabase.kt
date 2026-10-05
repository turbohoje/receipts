package cc.rocketscience.receipts.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Report::class, Receipt::class],
    version = 2,
    exportSchema = true,
)
abstract class ReceiptsDatabase : RoomDatabase() {

    abstract fun reportDao(): ReportDao
    abstract fun receiptDao(): ReceiptDao

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

        fun build(context: Context): ReceiptsDatabase =
            Room.databaseBuilder(context, ReceiptsDatabase::class.java, "receipts.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
