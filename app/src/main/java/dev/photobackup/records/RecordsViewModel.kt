package dev.photobackup.records

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.time.LocalDate

/** Export / import as the screens see them; [uri] is a SAF document picked by the user. */
interface RecordsActions {
    suspend fun export(uri: String): ExportResult
    suspend fun inspect(uri: String): InspectResult
    suspend fun apply(): ImportResult
    fun discard()
}

/** Production [RecordsActions]: opens the picked documents and hands the streams to the exporter / importer. */
class ContentRecordsActions(
    private val context: Context,
    private val exporter: RecordsExporter,
    private val importer: RecordsImporter,
) : RecordsActions {
    override suspend fun export(uri: String): ExportResult = withContext(Dispatchers.IO) {
        val out = try {
            context.contentResolver.openOutputStream(Uri.parse(uri), "wt")
        } catch (_: FileNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return@withContext ExportResult.Failed(RecordsMessages.EXPORT_FAILED)
        out.use { exporter.export(it) }
    }

    override suspend fun inspect(uri: String): InspectResult = withContext(Dispatchers.IO) {
        val input = try {
            context.contentResolver.openInputStream(Uri.parse(uri))
        } catch (_: FileNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return@withContext InspectResult.Rejected(RecordsMessages.NOT_RECORDS)
        input.use { importer.inspect(it) }
    }

    override suspend fun apply(): ImportResult = importer.apply()

    override fun discard() = importer.discard()
}

enum class RecordsWork { EXPORTING, CHECKING, IMPORTING }

data class RecordsUiState(
    /** What is running now; null = idle. */
    val working: RecordsWork? = null,
    /** A short fixed message (result or refusal); null = none. */
    val message: String? = null,
    /** The checked file holds this many items: the confirm dialog is open. Null = closed. */
    val confirmItems: Int? = null,
    /** The import is done: the app restarts now. */
    val restart: Boolean = false,
)

/** "Export records" / "Import records" (Settings) and "Have records from before? Import" (Setup). Never crashes. */
class RecordsViewModel(
    private val actions: RecordsActions,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    private val _state = MutableStateFlow(RecordsUiState())
    val state: StateFlow<RecordsUiState> = _state.asStateFlow()

    /** The name suggested in the save dialog. */
    fun fileName(): String = RecordsFormat.fileName(today())

    private fun idle() = _state.value.let { it.working == null && it.confirmItems == null && !it.restart }

    /** The save dialog returned [uri] (null = cancelled). */
    fun export(uri: String?) {
        if (uri == null || !idle()) return
        _state.update { it.copy(working = RecordsWork.EXPORTING, message = null) }
        viewModelScope.launch {
            val message = try {
                when (val r = actions.export(uri)) {
                    is ExportResult.Done -> RecordsMessages.exported(r.items)
                    is ExportResult.Failed -> r.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                RecordsMessages.EXPORT_FAILED
            }
            _state.update { it.copy(working = null, message = message) }
        }
    }

    /** The open dialog returned [uri] (null = cancelled): check the file, then ask to confirm. */
    fun pickImport(uri: String?) {
        if (uri == null || !idle()) return
        _state.update { it.copy(working = RecordsWork.CHECKING, message = null) }
        viewModelScope.launch {
            val r = try {
                actions.inspect(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                InspectResult.Rejected(RecordsMessages.IMPORT_FAILED)
            }
            _state.update {
                when (r) {
                    is InspectResult.Ready -> it.copy(working = null, confirmItems = r.items)
                    is InspectResult.Rejected -> it.copy(working = null, message = r.message)
                }
            }
        }
    }

    /** "Replace" in the confirm dialog. */
    fun confirmImport() {
        if (_state.value.confirmItems == null || _state.value.working != null) return
        _state.update { it.copy(confirmItems = null, working = RecordsWork.IMPORTING, message = null) }
        viewModelScope.launch {
            val r = try {
                actions.apply()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ImportResult.Failed(RecordsMessages.IMPORT_FAILED)
            }
            _state.update {
                when (r) {
                    // Stays "importing" until the process restarts.
                    is ImportResult.Done -> it.copy(
                        restart = true,
                        message = if (r.settingsRestored) null else RecordsMessages.SETTINGS_NOT_RESTORED,
                    )
                    is ImportResult.Failed -> it.copy(working = null, message = r.message)
                }
            }
        }
    }

    /** "Cancel" in the confirm dialog: the checked file is forgotten, nothing changed. */
    fun cancelImport() {
        if (_state.value.confirmItems == null) return
        actions.discard()
        _state.update { it.copy(confirmItems = null) }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}
