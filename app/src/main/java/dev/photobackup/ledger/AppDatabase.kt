package dev.photobackup.ledger

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.photobackup.review.ReviewAction

class Converters {
    @TypeConverter fun toName(s: BackupStatus): String = s.name
    @TypeConverter fun toStatus(s: String): BackupStatus = BackupStatus.valueOf(s)
    @TypeConverter fun toName(a: ReviewAction): String = a.name
    @TypeConverter fun toAction(s: String): ReviewAction = ReviewAction.valueOf(s)
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

/** v2 -> v3: bin columns on LedgerEntry, plus the ReviewDecision and ReviewKeep tables. Keeps every row. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `LedgerEntry` ADD COLUMN `binRequestedAt` INTEGER")
        db.execSQL("ALTER TABLE `LedgerEntry` ADD COLUMN `binPath` TEXT")
        db.execSQL("ALTER TABLE `LedgerEntry` ADD COLUMN `binnedAt` INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ReviewDecision` (`mediaId` INTEGER NOT NULL, `action` TEXT NOT NULL, " +
                "`decidedAt` INTEGER NOT NULL, PRIMARY KEY(`mediaId`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ReviewKeep` (`mediaId` INTEGER NOT NULL, `keptAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`mediaId`))"
        )
    }
}

@Database(
    entities = [LedgerEntry::class, FolderAssignment::class, ReviewDecision::class, ReviewKeep::class],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
    abstract fun folderAssignmentDao(): FolderAssignmentDao
    abstract fun reviewDecisionDao(): ReviewDecisionDao
    abstract fun reviewKeepDao(): ReviewKeepDao
}
