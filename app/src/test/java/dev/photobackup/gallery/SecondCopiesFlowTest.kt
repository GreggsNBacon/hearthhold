package dev.photobackup.gallery

import androidx.datastore.core.CorruptionException
import dev.photobackup.ledger.SecondCopy
import dev.photobackup.ledger.SecondStatus
import dev.photobackup.settings.AppSettings
import dev.photobackup.settings.SecondLocation
import dev.photobackup.smb.SmbConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The Gallery's second-copy flow never fails: a settings read error behaves as "no second location". */
class SecondCopiesFlowTest {
    private val off = AppSettings(smb = null, destination = null, deviceName = "Dev")
    private val on = off.copy(second = SecondLocation(SmbConfig("pc", "share", null, null), "Copy"))
    private val copy = SecondCopy(1, "x", null, null, SecondStatus.VERIFIED, 1, "abc", null, 0)

    @Test fun mapsTheTableWhileASecondLocationIsSet() = runBlocking {
        val out = secondCopiesFlow(flowOf(on)) { flowOf(listOf(copy)) }.toList()
        assertEquals(listOf(mapOf(1L to copy)), out)
    }

    @Test fun offEmitsNullWithoutObservingTheTable() = runBlocking {
        var observed = 0
        val out = secondCopiesFlow(flowOf(off)) { observed++; flowOf(listOf(copy)) }.toList()
        assertEquals(listOf(null), out)
        assertEquals(0, observed)
    }

    @Test fun aSettingsReadErrorBehavesAsNoSecondLocation() = runBlocking {
        val out = secondCopiesFlow(flow<AppSettings> { throw IOException("disk") }) { flowOf(listOf(copy)) }.toList()
        assertEquals(listOf(null), out)
        val corrupt = secondCopiesFlow(flow<AppSettings> { emit(on); throw CorruptionException("bad") }) {
            flowOf(listOf(copy))
        }.toList()
        assertNull(corrupt.last())
    }

    @Test fun aTableReadErrorFallsBackToNull() = runBlocking {
        val out = secondCopiesFlow(flowOf(on)) { flow { throw IllegalStateException("db") } }.toList()
        assertEquals(listOf(null), out)
    }

    @Test fun cancellationIsRethrown() = runBlocking {
        val r = runCatching {
            secondCopiesFlow(flow<AppSettings> { throw CancellationException("stop") }) { flowOf(emptyList()) }.toList()
        }
        assertTrue(r.exceptionOrNull() is CancellationException)
    }
}
