package dev.photobackup.ui.auth

import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.SmbConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthPromptTest {
    private var saved: SmbConfig? = SmbConfig("nas", "usb", "greg", "old")
    private val saves = mutableListOf<Pair<String, String>>()

    private var reauthSaved = 0

    private fun prompt() = AuthPrompt(
        load = { saved },
        save = { u, p -> saves += u to p; saved = saved?.copy(username = u, password = p) },
        onReauthSaved = { reauthSaved++ },
    )

    /** Fails with AuthFailed unless the password is "new". */
    private val attempts = mutableListOf<SmbConfig>()
    private val needsNew: suspend (SmbConfig) -> String = { cfg ->
        attempts += cfg
        if (cfg.password != "new") throw RemoteException.AuthFailed()
        "ok"
    }

    @Test fun successPassesThroughWithoutPrompt() = runTest {
        val p = prompt()
        assertEquals(42, p.withAuthRetry { cfg -> assertEquals("old", cfg.password); 42 })
        assertNull(p.request.value)
        assertTrue(saves.isEmpty())
    }

    @Test fun authFailedPromptsSavesAndRetriesOnce() = runTest {
        val p = prompt()
        val result = async { p.withAuthRetry(needsNew) }
        runCurrent()
        val req = p.request.value
        assertNotNull(req)
        assertEquals("greg", req!!.username) // pre-filled
        p.submit("greg", "new")
        assertEquals("ok", result.await())
        assertEquals(listOf("greg" to "new"), saves)
        assertEquals(listOf("old", "new"), attempts.map { it.password })
        assertNull(p.request.value)
    }

    @Test fun cancelPropagatesOriginalAuthFailedAndKeepsOldLogin() = runTest {
        val original = RemoteException.AuthFailed()
        val p = prompt()
        val result = async { runCatching { p.withAuthRetry<Unit> { throw original } } }
        runCurrent()
        assertNotNull(p.request.value)
        p.cancel()
        assertSame(original, result.await().exceptionOrNull())
        assertTrue(saves.isEmpty())
        assertEquals("old", saved?.password)
        assertNull(p.request.value)
    }

    @Test fun undecryptablePasswordPromptsBeforeFirstAttempt() = runTest {
        saved = SmbConfig("nas", "usb", "greg", null)
        val p = prompt()
        val result = async { p.withAuthRetry(needsNew) }
        runCurrent()
        assertNotNull(p.request.value)
        assertTrue(attempts.isEmpty()) // never tried with a null password
        p.submit("greg", "new")
        assertEquals("ok", result.await())
        assertEquals(1, attempts.size)
        assertEquals(listOf("greg" to "new"), saves)
    }

    @Test fun wrongNewPasswordRepromptsWithError() = runTest {
        val p = prompt()
        val result = async { p.withAuthRetry(needsNew) }
        runCurrent()
        p.submit("greg", "still wrong")
        runCurrent()
        assertEquals("Wrong username or password", p.request.value?.error)
        assertTrue(saves.isEmpty())
        p.submit("greg", "new")
        assertEquals("ok", result.await())
        assertEquals(listOf("greg" to "new"), saves)
    }

    @Test fun retryingStoreReconnectsWithNewPassword() = runTest {
        val fake = FakeRemoteStore()
        fake.dirs += "Photos"
        val configs = mutableListOf<SmbConfig>()
        val p = prompt()
        val store = AuthRetryingStore(p) { cfg ->
            configs += cfg
            fake.failOn = { op, _ -> if (op == "connect" && cfg.password != "new") RemoteException.AuthFailed() else null }
            fake
        }
        val listed = async { store.list("") }
        runCurrent()
        p.submit("greg", "new")
        assertEquals(listOf("Photos"), listed.await().map { it.name })
        store.list("") // reuses the open connection
        assertEquals(listOf("old", "new"), configs.map { it.password })
    }

    @Test fun noDriveConfiguredFails() = runTest {
        saved = null
        try {
            prompt().withAuthRetry { 1 }
            fail()
        } catch (_: DriveNotConfiguredException) {
        }
    }

    @Test fun reauthSavedCallbackRunsOnceAfterSuccessfulSave() = runTest {
        val p = prompt()
        val result = async { p.withAuthRetry(needsNew) }
        runCurrent()
        p.submit("greg", "still wrong")
        runCurrent()
        assertEquals(0, reauthSaved)
        p.submit("greg", "new")
        result.await()
        assertEquals(1, reauthSaved)
    }

    @Test fun reauthSavedCallbackNotRunOnCancelOrWithoutPrompt() = runTest {
        val p = prompt()
        assertEquals(42, p.withAuthRetry { 42 })
        val r = async { runCatching { p.withAuthRetry<Unit> { throw RemoteException.AuthFailed() } } }
        runCurrent()
        p.cancel()
        r.await()
        assertEquals(0, reauthSaved)
    }

    @Test fun savedLoginIsTriedBeforeAnyPrompt() = runTest {
        val p = prompt()
        p.withAuthRetry { cfg -> assertEquals("old", cfg.password) }
        assertNull(p.request.value)
        assertEquals(0, reauthSaved)
    }


    @Test fun failingReauthFollowUpDoesNotTurnAnAcceptedLoginIntoAFailure() = runTest {
        val p = AuthPrompt(
            load = { saved },
            save = { u, pw -> saves += u to pw; saved = saved?.copy(username = u, password = pw) },
            onReauthSaved = { throw IllegalStateException("scheduler down") },
        )
        val result = async { p.withAuthRetry(needsNew) }
        runCurrent()
        p.submit("greg", "new")
        assertEquals("ok", result.await())
        assertEquals(listOf("greg" to "new"), saves) // saved once, not re-saved by an error path
    }
}
