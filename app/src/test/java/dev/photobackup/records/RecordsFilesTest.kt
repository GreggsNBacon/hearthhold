package dev.photobackup.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The swap is a rename at app start: a crash at any point leaves the old DB or the new one, never a mix. */
class RecordsFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun f(name: String) = File(tmp.root, name)

    @Test fun readyReplacesLiveAndDropsItsOldWalAndJournal() {
        f("ledger.db").writeText("old")
        f("ledger.db-wal").writeText("old wal frames")
        f("ledger.db-shm").writeText("shm")
        f("ledger.db-journal").writeText("journal")
        f("ledger-import.db").writeText("new")
        assertTrue(RecordsFiles.promoteIfReady(tmp.root))
        assertEquals("new", f("ledger.db").readText())
        for (n in listOf("ledger.db-wal", "ledger.db-shm", "ledger.db-journal", "ledger-import.db")) assertFalse(n, f(n).exists())
    }

    @Test fun readyWithoutLiveBecomesLive() {
        f("ledger-import.db").writeText("new")
        assertTrue(RecordsFiles.promoteIfReady(tmp.root))
        assertEquals("new", f("ledger.db").readText())
    }

    @Test fun crashAfterSidecarsWereDeletedStillPromotesNextTime() {
        f("ledger.db").writeText("old")
        f("ledger-import.db").writeText("new")
        // As if the previous start died after deleting the sidecars but before the rename.
        assertTrue(RecordsFiles.promoteIfReady(tmp.root))
        assertEquals("new", f("ledger.db").readText())
        assertFalse(RecordsFiles.promoteIfReady(tmp.root))
        assertEquals("new", f("ledger.db").readText())
    }

    @Test fun stagingIsNeverPromoted() {
        f("ledger.db").writeText("old")
        f("ledger.db-wal").writeText("old wal")
        f("ledger-import.staging").writeText("half written")
        assertFalse(RecordsFiles.promoteIfReady(tmp.root))
        assertEquals("old", f("ledger.db").readText())
        assertEquals("old wal", f("ledger.db-wal").readText())
    }

    @Test fun commitMovesStagingToReadyAndRefusesALeftoverWal() {
        f("ledger-import.staging").writeText("new")
        f("ledger-import.staging-journal").writeText("")
        RecordsFiles.commit(tmp.root)
        assertEquals("new", f("ledger-import.db").readText())
        assertFalse(f("ledger-import.staging").exists())
        assertFalse(f("ledger-import.staging-journal").exists())

        f("ledger-import.db").delete()
        f("ledger-import.staging").writeText("new")
        f("ledger-import.staging-wal").writeText("unmerged frames")
        try {
            RecordsFiles.commit(tmp.root)
            throw AssertionError("commit should refuse")
        } catch (_: java.io.IOException) {
        }
        assertFalse(f("ledger-import.db").exists())
    }

    @Test fun clearStagingRemovesEverythingButLive() {
        f("ledger.db").writeText("old")
        f("ledger-import.staging").writeText("x")
        f("ledger-import.staging-journal").writeText("x")
        RecordsFiles.clearStaging(tmp.root)
        assertFalse(f("ledger-import.staging").exists())
        assertFalse(f("ledger-import.staging-journal").exists())
        assertEquals("old", f("ledger.db").readText())
    }
}
