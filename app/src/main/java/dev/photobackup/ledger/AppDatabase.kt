package dev.photobackup.ledger

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter fun toName(s: BackupStatus): String = s.name
    @TypeConverter fun toStatus(s: String): BackupStatus = BackupStatus.valueOf(s)
}

/** v1 -> v2: adds the FolderAssignment table and LedgerEntry.movingTo. Keeps every ledger row. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `LedgerEntry` ADD COLUMN `movingTo` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `FolderAssignment` (`mediaId` INTEGER NOT NULL, `folder` TEXT NOT NULL, " +
                "PRIMARY KEY(`mediaId`))"
        )
    }
}

@Database(entities = [LedgerEntry::class, FolderAssignment::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
    abstract fun folderAssignmentDao(): FolderAssignmentDao
}
