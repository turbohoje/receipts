package cc.rocketscience.receipts.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Report::class, Receipt::class],
    version = 1,
    exportSchema = true,
)
abstract class ReceiptsDatabase : RoomDatabase() {

    abstract fun reportDao(): ReportDao
    abstract fun receiptDao(): ReceiptDao

    companion object {
        fun build(context: Context): ReceiptsDatabase =
            Room.databaseBuilder(context, ReceiptsDatabase::class.java, "receipts.db")
                .build()
    }
}
