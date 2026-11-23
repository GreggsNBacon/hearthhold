package dev.photobackup.gallery

import dev.photobackup.ledger.SecondCopy
import dev.photobackup.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The second location's copies by mediaId for the Gallery badge, or null while no second location is set (then the
 * table is not even observed). Never fails: a table read error falls back to null for that observation only (a later
 * settings change starts a new one); a settings read error (IOException, CorruptionException, ...) ends the flow with
 * null, i.e. badges as without the feature. Cancellation is rethrown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun secondCopiesFlow(
    settings: Flow<AppSettings>,
    observeCopies: () -> Flow<List<SecondCopy>>,
): Flow<Map<Long, SecondCopy>?> =
    settings.map { it.second != null }.distinctUntilChanged().flatMapLatest { on ->
        if (!on) flowOf(null)
        else observeCopies().map<List<SecondCopy>, Map<Long, SecondCopy>?> { rows ->
            rows.associateBy { it.mediaId }
        }.catch { e -> if (e is CancellationException) throw e else emit(null) }
    }.catch { e -> if (e is CancellationException) throw e else emit(null) }
