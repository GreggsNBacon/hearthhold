package dev.photobackup.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.photobackup.backup.BackupEngine
import dev.photobackup.media.SourceFolders
import dev.photobackup.review.DEFAULT_GESTURES
import dev.photobackup.review.ReviewAction
import dev.photobackup.review.Swipe
import dev.photobackup.smb.SmbConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore internal constructor(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val cipher: CredentialCipher,
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context, cipher: CredentialCipher) :
        this(context, cipher, context.applicationContext.settingsDataStore)

    val settings: Flow<AppSettings> = dataStore.data.map { read(it) }

    /**
     * Read-transform-write. When the transform leaves the drive login unchanged, the stored login (including the
     * password ciphertext) is left exactly as it is: if the password is momentarily undecryptable (a Keystore
     * hiccup reads as null), an unrelated edit such as a toggle must not erase it.
     */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val before = read(prefs)
            val after = transform(before)
            write(prefs, after, writeLogin = after.smb != before.smb)
        }
    }

    private fun read(p: Preferences): AppSettings {
        val host = p[HOST]
        val share = p[SHARE]
        val smb = if (!host.isNullOrBlank() && !share.isNullOrBlank()) {
            SmbConfig(host, share, p[USER], p[PASSWORD]?.let { decryptOrNull(it) })
        } else null
        return AppSettings(
            smb = smb,
            destination = p[DESTINATION],
            deviceName = p[DEVICE_NAME] ?: AppSettings(null, null).deviceName,
            chargingOnly = p[CHARGING_ONLY] ?: false,
            freeUpAgeMonths = p[FREE_UP_MONTHS] ?: 6,
            folders = SourceFolders(
                camera = p[CAMERA] ?: true,
                screenshots = p[SCREENSHOTS] ?: true,
                extraRelativePaths = p[EXTRA_PATHS] ?: emptySet(),
            ),
            loopVideos = p[LOOP_VIDEOS] ?: true,
            notifyOnComplete = p[NOTIFY_ON_COMPLETE] ?: true,
            parallelUploads = (p[PARALLEL_UPLOADS] ?: 1).coerceIn(1, BackupEngine.MAX_PARALLEL),
            overnightOnly = p[OVERNIGHT_ONLY] ?: false,
            overnightStartHour = (p[OVERNIGHT_START] ?: 1).coerceIn(0, 23),
            overnightEndHour = (p[OVERNIGHT_END] ?: 6).coerceIn(0, 23),
            folderRules = (p[FOLDER_RULES] ?: emptySet()).mapNotNull { decodeRule(it) }.toMap(),
            gestures = readGestures(p),
        )
    }

    /** One string per direction; a missing or unknown value is that direction's default. Never all "No action". */
    private fun readGestures(p: Preferences): Map<Swipe, ReviewAction?> {
        val m = Swipe.values().associateWith { swipe ->
            when (val v = p[gestureKey(swipe)]) {
                null -> DEFAULT_GESTURES[swipe]
                NO_ACTION -> null
                else -> ReviewAction.values().firstOrNull { it.name == v } ?: DEFAULT_GESTURES[swipe]
            }
        }
        return if (m.values.all { it == null }) DEFAULT_GESTURES else m
    }

    /** A lost key or corrupt value must not block the user from re-entering credentials. */
    private fun decryptOrNull(enc: String): String? =
        try { cipher.decrypt(enc) } catch (e: Exception) { null }

    private fun write(p: MutablePreferences, s: AppSettings, writeLogin: Boolean) {
        if (writeLogin) {
            p.putOrRemove(HOST, s.smb?.host)
            p.putOrRemove(SHARE, s.smb?.share)
            p.putOrRemove(USER, s.smb?.username)
            p.putOrRemove(PASSWORD, s.smb?.password?.let { cipher.encrypt(it) })
        }
        p.putOrRemove(DESTINATION, s.destination)
        p[DEVICE_NAME] = s.deviceName
        p[CHARGING_ONLY] = s.chargingOnly
        p[FREE_UP_MONTHS] = s.freeUpAgeMonths
        p[CAMERA] = s.folders.camera
        p[SCREENSHOTS] = s.folders.screenshots
        p[EXTRA_PATHS] = s.folders.extraRelativePaths
        p[LOOP_VIDEOS] = s.loopVideos
        p[NOTIFY_ON_COMPLETE] = s.notifyOnComplete
        p[PARALLEL_UPLOADS] = s.parallelUploads
        p[OVERNIGHT_ONLY] = s.overnightOnly
        p[OVERNIGHT_START] = s.overnightStartHour
        p[OVERNIGHT_END] = s.overnightEndHour
        for (swipe in Swipe.values()) p[gestureKey(swipe)] = s.gestures[swipe]?.name ?: NO_ACTION
        if (s.folderRules.isEmpty()) p.remove(FOLDER_RULES)
        else p[FOLDER_RULES] = s.folderRules.map { (path, name) -> "$path$RULE_SEP$name" }.toSet()
    }

    private fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    private companion object {
        const val NO_ACTION = "NONE"
        fun gestureKey(s: Swipe) = stringPreferencesKey("gesture_" + s.name.lowercase())

        const val RULE_SEP = "\u0000"
        val FOLDER_RULES = stringSetPreferencesKey("folder_rules")

        fun decodeRule(entry: String): Pair<String, String>? {
            val i = entry.indexOf(RULE_SEP)
            return if (i <= 0 || i == entry.length - 1) null else entry.substring(0, i) to entry.substring(i + 1)
        }

        val HOST = stringPreferencesKey("smb_host")
        val SHARE = stringPreferencesKey("smb_share")
        val USER = stringPreferencesKey("smb_username")
        val PASSWORD = stringPreferencesKey("smb_password")
        val DESTINATION = stringPreferencesKey("destination")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val CHARGING_ONLY = booleanPreferencesKey("charging_only")
        val FREE_UP_MONTHS = intPreferencesKey("free_up_age_months")
        val CAMERA = booleanPreferencesKey("folder_camera")
        val SCREENSHOTS = booleanPreferencesKey("folder_screenshots")
        val EXTRA_PATHS = stringSetPreferencesKey("folder_extra_paths")
        val LOOP_VIDEOS = booleanPreferencesKey("loop_videos")
        val NOTIFY_ON_COMPLETE = booleanPreferencesKey("notify_on_complete")
        val PARALLEL_UPLOADS = intPreferencesKey("parallel_uploads")
        val OVERNIGHT_ONLY = booleanPreferencesKey("overnight_only")
        val OVERNIGHT_START = intPreferencesKey("overnight_start_hour")
        val OVERNIGHT_END = intPreferencesKey("overnight_end_hour")
    }
}
