package dev.photobackup.app

import dev.photobackup.review.CHANGED_WHILE_FINISHING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** kotlinc reads sources as UTF-8: a stray Windows-1252 byte would ship as U+FFFD in user-facing text. */
class SourceEncodingTest {
    @Test fun every_main_source_file_is_valid_utf8() {
        val root = File("src/main/java")
        assertTrue("run from the app module: ${root.absolutePath}", root.isDirectory)
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size > 50)
        val bad = files.filter { f ->
            try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(f.readBytes()))
                false
            } catch (_: CharacterCodingException) {
                true
            }
        }
        assertEquals("not valid UTF-8", emptyList<String>(), bad.map { it.path })
    }

    @Test fun finish_text_has_a_real_em_dash() {
        assertTrue(CHANGED_WHILE_FINISHING.contains('—'))
        assertTrue(!CHANGED_WHILE_FINISHING.contains('�'))
    }
}
