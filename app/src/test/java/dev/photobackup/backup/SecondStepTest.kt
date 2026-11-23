package dev.photobackup.backup

import dev.photobackup.settings.AppSettings
import dev.photobackup.settings.SecondLocation
import dev.photobackup.smb.DriveInfo
import dev.photobackup.smb.FakeRemoteStore
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.RemoteStore
import dev.photobackup.smb.SmbConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SecondStepTest {
    private val mainCfg = SmbConfig("main", "share", "u", "p")
    private val secondCfg = SmbConfig("second", "share2", "u2", "p2")
    private val second = SecondLocation(secondCfg, "S")

    private class Tracked(val inner: FakeRemoteStore = FakeRemoteStore()) : RemoteStore by inner {
        var closed = false
        var connects = 0
        override suspend fun connect(): DriveInfo { connects++; return inner.connect() }
        override fun close() { closed = true }
    }

    private class Env(val settings: AppSettings) {
        val opened = mutableListOf<SmbConfig>()
        val stores = mutableMapOf<SmbConfig, Tracked>()
        var settingsReads = 0
        var engineRuns = 0
        var engineResult: suspend (SecondEnv) -> SecondResult = { SecondResult.Done(1, 0, 0, 0) }

        fun step(read: suspend () -> AppSettings = { settingsReads++; settings }) = SecondStep(
            readSettings = read,
            openStore = { cfg -> opened += cfg; stores.getOrPut(cfg) { Tracked() } },
            runEngine = { e -> engineRuns++; engineResult(e) },
        )
    }

    private fun settings(second: SecondLocation?) = AppSettings(null, null, deviceName = "Dev", second = second)
    private val done = BackupResult.Done(2, 0, 0)

    private fun around(env: Env, mainStore: RemoteStore = Tracked(), main: suspend () -> BackupResult) =
        runBlocking { env.step().around(mainCfg, mainStore, main) }

    @Test fun offSkipsEntirely() {
        val env = Env(settings(null))
        val r = around(env) { done }
        assertNull((r as BackupResult.Done).second)
        assertEquals(done, r)
        assertEquals(0, env.engineRuns)
        assertTrue(env.opened.isEmpty()) // no connection at all
    }

    @Test fun settingsAreReadAfterTheMainRun() {
        val env = Env(settings(second))
        var mainDone = false
        val step = env.step { assertTrue(mainDone); env.settings }
        runBlocking { step.around(mainCfg, Tracked()) { mainDone = true; done } }
        assertEquals(1, env.engineRuns)
    }

    @Test fun removedMeanwhileSkips() { // second switched off between the gates and the second step
        val env = Env(settings(second))
        val step = env.step { settings(null) }
        val r = runBlocking { step.around(mainCfg, Tracked()) { done } }
        assertNull((r as BackupResult.Done).second)
        assertEquals(0, env.engineRuns)
    }

    @Test fun mainDoneRunsSecondOnOwnSessionAndReusesMainSession() {
        val env = Env(settings(second))
        val main = Tracked()
        var seen: RemoteStore? = null
        env.engineResult = { e -> seen = e.mainStore(); SecondResult.Done(3, 1, 0, 0) }
        val r = around(env, main) { done } as BackupResult.Done
        assertEquals(SecondResult.Done(3, 1, 0, 0), r.second)
        assertEquals(2, r.uploaded)
        assertEquals(listOf(secondCfg), env.opened) // only the second session was opened
        assertSame(main, seen)
        assertTrue(env.stores.getValue(secondCfg).closed)
        assertFalse(main.closed) // the runner owns the main store it created
    }

    @Test fun mainUnreachableStillRunsSecondWithFreshMainSession() {
        val env = Env(settings(second))
        var seen: RemoteStore? = null
        env.engineResult = { e -> seen = e.mainStore(); SecondResult.Done(0, 0, 0, 0) }
        val r = around(env) { BackupResult.Stopped(RemoteException.Unreachable()) } as BackupResult.Stopped
        assertTrue(r.reason is RemoteException.Unreachable)
        assertEquals(SecondResult.Done(0, 0, 0, 0), r.second)
        assertEquals(listOf(secondCfg, mainCfg), env.opened)
        val lazyMain = env.stores.getValue(mainCfg)
        assertSame(lazyMain, seen)
        assertEquals(1, lazyMain.connects)
        assertTrue(lazyMain.closed)
        assertTrue(env.stores.getValue(secondCfg).closed)
    }

    @Test fun lazyMainSessionIsOpenedOnlyWhenAsked() {
        val env = Env(settings(second))
        env.engineResult = { SecondResult.Done(0, 0, 0, 0) } // never asks for the main store
        around(env) { BackupResult.Stopped(RemoteException.Unreachable()) }
        assertEquals(listOf(secondCfg), env.opened)
    }

    @Test fun lazyMainThatCannotConnectGivesNull() {
        val env = Env(settings(second))
        val bad = Tracked(FakeRemoteStore(failOn = { op, _ -> if (op == "connect") RemoteException.Unreachable() else null }))
        env.stores[mainCfg] = bad
        var seen: RemoteStore? = bad
        env.engineResult = { e -> seen = e.mainStore(); SecondResult.Done(0, 0, 0, 1) }
        around(env) { BackupResult.Stopped(RemoteException.Unreachable()) }
        assertNull(seen)
        assertTrue(bad.closed)
    }

    @Test fun mainAuthFailureStillRunsSecond() {
        val env = Env(settings(second))
        val r = around(env) { BackupResult.Stopped(RemoteException.AuthFailed()) } as BackupResult.Stopped
        assertTrue(r.reason is RemoteException.AuthFailed)
        assertEquals(1, env.engineRuns)
        assertEquals(SecondResult.Done(1, 0, 0, 0), r.second)
    }

    @Test fun mainDoneWithSecondUnreachableStaysGoodMainRun() {
        val env = Env(settings(second))
        env.engineResult = { SecondResult.Unreachable }
        val r = around(env) { done } as BackupResult.Done
        assertEquals(2, r.uploaded)
        assertEquals(SecondResult.Unreachable, r.second)
    }

    @Test fun cancellationSkipsSecond() {
        val env = Env(settings(second))
        try { around(env) { throw CancellationException("window closed") }; throw AssertionError("no throw") }
        catch (_: CancellationException) { }
        assertEquals(0, env.engineRuns)
        assertEquals(0, env.settingsReads)
        assertTrue(env.opened.isEmpty())
    }

    @Test fun setupIncompleteSkipsSecond() {
        val env = Env(settings(second))
        try { around(env) { throw SetupIncompleteException() }; throw AssertionError("no throw") }
        catch (_: SetupIncompleteException) { }
        assertEquals(0, env.engineRuns)
        assertTrue(env.opened.isEmpty())
    }

    @Test fun cancelledDuringSecondPropagatesAndClosesEverything() {
        val env = Env(settings(second))
        var lazy: Tracked? = null
        env.engineResult = { e -> lazy = e.mainStore() as Tracked; throw CancellationException("stop") }
        try { around(env) { BackupResult.Stopped(RemoteException.Unreachable()) }; throw AssertionError("no throw") }
        catch (_: CancellationException) { }
        assertTrue(env.stores.getValue(secondCfg).closed)
        assertTrue(lazy!!.closed)
    }

    @Test fun unexpectedSecondErrorNeverFailsTheMainRun() {
        val env = Env(settings(second))
        env.engineResult = { throw IllegalStateException("secret-host details") }
        val r = around(env) { done } as BackupResult.Done
        assertEquals(SecondResult.Unreachable, r.second)
        assertTrue(env.stores.getValue(secondCfg).closed)
    }

    @Test fun settingsReadFailureSkipsSecond() {
        val env = Env(settings(second))
        val step = env.step { throw IOException("boom") }
        val r = runBlocking { step.around(mainCfg, Tracked()) { done } } as BackupResult.Done
        assertNull(r.second)
        assertEquals(0, env.engineRuns)
    }
}
