package dev.photobackup.records

import dev.photobackup.media.SourceFolders
import dev.photobackup.review.DEFAULT_GESTURES
import dev.photobackup.review.ReviewAction
import dev.photobackup.review.Swipe
import dev.photobackup.settings.AppSettings
import dev.photobackup.settings.SecondLocation
import dev.photobackup.smb.SmbConfig
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate

/**
 * A records file: a zip with [ENTRY_DB] (a consistent copy of `ledger.db`), [ENTRY_SETTINGS] (every setting except
 * the passwords) and [ENTRY_META] ([RecordsMeta]).
 */
object RecordsFormat {
    const val FORMAT_VERSION = 1

    /** Must equal AppDatabase's version (a test checks). */
    const val SCHEMA_VERSION = 5

    const val ENTRY_DB = "ledger.db"
    const val ENTRY_SETTINGS = "settings.json"
    const val ENTRY_META = "meta.json"

    fun fileName(date: LocalDate): String =
        "hearthhold-records-%04d%02d%02d.hearthhold".format(date.year, date.monthValue, date.dayOfMonth)
}

/** Fixed user-facing texts. Never exception text, paths or file contents. */
object RecordsMessages {
    const val BUSY = "A backup or restore is running — try again when it's done"
    const val CONFIRM = "Replace this app's records with the file's? Same phone only."
    const val NOT_RECORDS = "That file isn't a Hearthhold records file"
    const val UNSUPPORTED = "That records file is from a different version of Hearthhold and can't be read"
    const val NEWER = "That records file is from a newer Hearthhold — update the app first"
    const val DAMAGED = "That records file is damaged and can't be opened"
    const val MEDIA_ACCESS = "Allow access to all photos first, then import again"
    const val IMPORT_FAILED = "Couldn't import the records — nothing was changed"
    const val EXPORT_FAILED = "Couldn't export the records"
    const val SETTINGS_NOT_RESTORED = "Records imported, but the settings couldn't be restored — set up the drive again"

    fun exported(items: Int) = "Exported $items items"
}

sealed interface ExportResult {
    data class Done(val items: Int) : ExportResult
    data class Failed(val message: String) : ExportResult
}

sealed interface InspectResult {
    /** The file is usable; [items] ledger rows (after migrating). Nothing has been replaced yet. */
    data class Ready(val items: Int) : InspectResult
    data class Rejected(val message: String) : InspectResult
}

sealed interface ImportResult {
    /** [items] rows kept, [dropped] phone rows removed because their photo id now points elsewhere (or nowhere). */
    data class Done(val items: Int, val dropped: Int, val settingsRestored: Boolean) : ImportResult
    data class Failed(val message: String) : ImportResult
}

data class RecordsMeta(val formatVersion: Int, val schemaVersion: Int, val appVersion: String, val itemCount: Int) {
    fun toJson(): String = JSONObject()
        .put("formatVersion", formatVersion)
        .put("schemaVersion", schemaVersion)
        .put("appVersion", appVersion)
        .put("itemCount", itemCount)
        .toString()

    companion object {
        /** Null when [json] is not a complete meta object. */
        fun parse(json: String): RecordsMeta? = try {
            val o = JSONObject(json)
            RecordsMeta(o.getInt("formatVersion"), o.getInt("schemaVersion"), o.getString("appVersion"), o.getInt("itemCount"))
        } catch (_: JSONException) {
            null
        }
    }
}

/** The non-secret settings as JSON. There is no password field at all, for either login. */
object SettingsJson {
    private const val NO_ACTION = "NONE"

    fun encode(s: AppSettings): String {
        val o = JSONObject()
        s.smb?.let { o.put("drive", login(it)) }
        s.destination?.let { o.put("destination", it) }
        o.put("deviceName", s.deviceName)
        o.put("chargingOnly", s.chargingOnly)
        o.put("freeUpAgeMonths", s.freeUpAgeMonths)
        o.put("camera", s.folders.camera)
        o.put("screenshots", s.folders.screenshots)
        o.put("extraFolders", JSONArray(s.folders.extraRelativePaths.sorted()))
        o.put("loopVideos", s.loopVideos)
        o.put("notifyOnComplete", s.notifyOnComplete)
        o.put("parallelUploads", s.parallelUploads)
        o.put("overnightOnly", s.overnightOnly)
        o.put("overnightStartHour", s.overnightStartHour)
        o.put("overnightEndHour", s.overnightEndHour)
        o.put("folderRules", JSONObject().apply { s.folderRules.forEach { (k, v) -> put(k, v) } })
        o.put("gestures", JSONObject().apply { Swipe.values().forEach { put(it.name, s.gestures[it]?.name ?: NO_ACTION) } })
        s.second?.let { o.put("second", login(it.smb).put("destination", it.destination)) }
        o.put("freeUpNeedsBoth", s.freeUpNeedsBoth)
        o.put("staleWarnDays", s.staleWarnDays)
        return o.toString()
    }

    private fun login(c: SmbConfig) = JSONObject().put("host", c.host).put("share", c.share)
        .apply { c.username?.let { put("username", it) } }

    /** Throws [JSONException] on malformed JSON. Missing values take the app's defaults; passwords are always null. */
    fun decode(json: String): AppSettings {
        val o = JSONObject(json)
        val d = AppSettings(null, null)
        val drive = o.optJSONObject("drive")?.let { readLogin(it) }
        val second = o.optJSONObject("second")?.let { j ->
            val login = readLogin(j)
            val dest = j.optString("destination")
            if (login != null && dest.isNotBlank()) SecondLocation(login, dest) else null
        }
        val extras = o.optJSONArray("extraFolders")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val rules = o.optJSONObject("folderRules")?.let { r -> r.keys().asSequence().associateWith { r.getString(it) } }
        val gestures = o.optJSONObject("gestures")?.let { g ->
            val m = Swipe.values().associateWith { swipe ->
                when (val v = g.optString(swipe.name, "")) {
                    "" -> DEFAULT_GESTURES[swipe]
                    NO_ACTION -> null
                    else -> ReviewAction.values().firstOrNull { it.name == v } ?: DEFAULT_GESTURES[swipe]
                }
            }
            if (m.values.all { it == null }) DEFAULT_GESTURES else m
        }
        return AppSettings(
            smb = drive,
            destination = if (o.has("destination")) o.getString("destination") else null,
            deviceName = o.optString("deviceName", "").ifBlank { d.deviceName },
            chargingOnly = o.optBoolean("chargingOnly", d.chargingOnly),
            freeUpAgeMonths = o.optInt("freeUpAgeMonths", d.freeUpAgeMonths),
            folders = SourceFolders(
                camera = o.optBoolean("camera", d.folders.camera),
                screenshots = o.optBoolean("screenshots", d.folders.screenshots),
                extraRelativePaths = extras ?: d.folders.extraRelativePaths,
            ),
            loopVideos = o.optBoolean("loopVideos", d.loopVideos),
            notifyOnComplete = o.optBoolean("notifyOnComplete", d.notifyOnComplete),
            parallelUploads = o.optInt("parallelUploads", d.parallelUploads),
            overnightOnly = o.optBoolean("overnightOnly", d.overnightOnly),
            overnightStartHour = o.optInt("overnightStartHour", d.overnightStartHour),
            overnightEndHour = o.optInt("overnightEndHour", d.overnightEndHour),
            folderRules = rules ?: d.folderRules,
            gestures = gestures ?: d.gestures,
            second = second,
            freeUpNeedsBoth = o.optBoolean("freeUpNeedsBoth", d.freeUpNeedsBoth),
            staleWarnDays = o.optInt("staleWarnDays", d.staleWarnDays),
        )
    }

    private fun readLogin(j: JSONObject): SmbConfig? {
        val host = j.optString("host")
        val share = j.optString("share")
        if (host.isBlank() || share.isBlank()) return null
        val user = if (j.has("username")) j.getString("username") else null
        return SmbConfig(host, share, user, null)
    }
}
