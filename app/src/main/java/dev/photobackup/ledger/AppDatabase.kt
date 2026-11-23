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
    @TypeConverter fun toName(s: SecondStatus): String = s.name
    @TypeConverter fun toSecondStatus(s: String): SecondStatus = SecondStatus.valueOf(s)
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

/** v3 -> v4: the SecondCopy table (second backup location). Keeps every row; empty until the feature is used. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `SecondCopy` (`mediaId` INTEGER NOT NULL, `remotePath` TEXT, `partPath` TEXT, " +
                "`movingTo` TEXT, `status` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `sha256` TEXT, " +
                "`lastError` TEXT, `updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`mediaId`))"
        )
    }
}

/** v4 -> v5: health check's lastCheckedAt on LedgerEntry and SecondCopy (null = never checked). Keeps every row. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `LedgerEntry` ADD COLUMN `lastCheckedAt` INTEGER")
        db.execSQL("ALTER TABLE `SecondCopy` ADD COLUMN `lastCheckedAt` INTEGER")
    }
}

@Database(
    entities = [
        LedgerEntry::class, FolderAssignment::class, ReviewDecision::class, ReviewKeep::class, SecondCopy::class,
    ],
    version = 5,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
    abstract fun folderAssignmentDao(): FolderAssignmentDao
    abstract fun reviewDecisionDao(): ReviewDecisionDao
    abstract fun reviewKeepDao(): ReviewKeepDao
    abstract fun secondCopyDao(): SecondCopyDao
    abstract fun restoreDao(): RestoreDao
}
