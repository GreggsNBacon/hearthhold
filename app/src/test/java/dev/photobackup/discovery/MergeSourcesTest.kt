package dev.photobackup.discovery

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MergeSourcesTest {
    @Test fun failingSourceDoesNotStopOthersAndFlowCompletes() = runTest {
        val out = mergeSources(
            200,
            listOf(
                { _, _ -> throw IllegalStateException("interface gone") },
                { _, emit -> emit(null, "10.0.0.5") },
                { _, emit -> emit("NAS", "10.0.0.5"); emit("NAS", "10.0.0.5") },
            ),
        ).toList()
        assertEquals(listOf(FoundHost(null, "10.0.0.5"), FoundHost("NAS", "10.0.0.5")), out)
    }
}
