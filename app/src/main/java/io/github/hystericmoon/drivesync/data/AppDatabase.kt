package io.github.hystericmoon.drivesync.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FolderPair::class, BaselineEntry::class, SyncLogEntry::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pairs(): PairDao
    abstract fun baseline(): BaselineDao
    abstract fun log(): SyncLogDao

    companion object {
        /** v2: per-pair exclude patterns + sync history. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `folder_pairs` ADD COLUMN `excludePatterns` TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`pairId` INTEGER NOT NULL, `time` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
                        "`path` TEXT NOT NULL, `detail` TEXT, FOREIGN KEY(`pairId`) REFERENCES " +
                        "`folder_pairs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_log_pairId` ON `sync_log` (`pairId`)")
            }
        }

        /** v3: skip notes that outlive a single sync run. */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `folder_pairs` ADD COLUMN `skippedItems` TEXT NOT NULL DEFAULT ''")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "drivesync.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
