package dev.photobackup.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import dev.photobackup.smb.AccessDeniedException
import dev.photobackup.smb.RemoteException
import dev.photobackup.smb.RemoteStore
import dev.photobackup.smb.SmbUri
import kotlinx.coroutines.runBlocking
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/**
 * Media3 [DataSource] for drive videos: `smb://host/share/<path>` (see [SmbUri]) read through [store], which supplies
 * the saved login (an `AuthRetryingStore`, so a refused login prompts once and retries).
 *
 * ExoPlayer calls [open], [read] and [close] on its loading thread, never the main thread, so the suspending store is
 * bridged with `runBlocking` there. [DataSpec.position] maps to `openRead(path, offset)` (a seek reopens at the new
 * offset); [DataSpec.length] bounds the read, and an unbounded read runs to the file's end. Every failure is an
 * [IOException]; the drive error stays in the cause chain for the page's message. The store is not closed here: it
 * belongs to the player page.
 */
@UnstableApi
class SmbDataSource(private val store: RemoteStore) : BaseDataSource(/* isNetwork = */ true) {
    private var dataSpec: DataSpec? = null
    private var stream: InputStream? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)
        val loc = SmbUri.parse(dataSpec.uri.toString()) ?: throw IOException("Not an smb:// URI")
        val position = dataSpec.position
        val size = io { store.stat(loc.path) }?.sizeBytes ?: throw FileNotFoundException("Not on the drive")
        if (position > size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        val available = size - position
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(dataSpec.length, available)
        stream = io { store.openRead(loc.path, position) }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val s = stream ?: throw IOException("Not open")
        val n = try {
            s.read(buffer, offset, minOf(length.toLong(), bytesRemaining).toInt())
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Drive read failed", e)
        }
        if (n < 0) throw EOFException("The drive file ended early") // shorter than its size said
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun close() {
        dataSpec = null
        val s = stream
        stream = null
        try {
            s?.close()
        } catch (_: Exception) {
            // already broken; nothing to do
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    /** Runs a store call on the loading thread; maps interrupts and non-IO failures to [IOException]. */
    private fun <T> io(block: suspend () -> T): T = try {
        runBlocking { block() }
    } catch (e: IOException) {
        throw e
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Cancelled")
    } catch (e: Exception) {
        throw IOException("Drive access failed", e)
    }

    /** Each data source uses the page's own store from [storeProvider]. */
    class Factory(private val storeProvider: () -> RemoteStore) : DataSource.Factory {
        override fun createDataSource(): SmbDataSource = SmbDataSource(storeProvider())
    }

    companion object {
        /**
         * False for errors a retry can't fix and that must not prompt again: a refused (or cancelled) login, a
         * missing file, no permission. A dropped connection is retried (the store reconnects).
         */
        fun isRetryable(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(16).none {
            it is RemoteException.AuthFailed || it is FileNotFoundException || it is AccessDeniedException ||
                it is RemoteException.ShareNotFound || it is RemoteException.Smb1Only
        }

        /** ExoPlayer's default retries, minus the errors [isRetryable] rules out. */
        fun loadErrorPolicy(): LoadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                if (isRetryable(info.exception)) super.getRetryDelayMsFor(info) else C.TIME_UNSET
        }
    }
}
