package dev.photobackup.ledger

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun toName(s: BackupStatus): String = s.name
    @TypeConverter fun toStatus(s: String): BackupStatus = BackupStatus.valueOf(s)
}

@Database(entities = [LedgerEntry::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
}
