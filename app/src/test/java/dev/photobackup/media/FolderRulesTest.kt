package dev.photobackup.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderRulesTest {
    private val rules = mapOf("Pictures/WhatsApp/" to "Memes", "Pictures/" to "Pics")

    @Test fun longestPrefixWins() {
        assertEquals("Memes", FolderRules.ruleFor("Pictures/WhatsApp/Media/", rules))
        assertEquals("Pics", FolderRules.ruleFor("Pictures/Other/", rules))
        assertNull(FolderRules.ruleFor("DCIM/Camera/", rules))
    }

    @Test fun matchIsCaseInsensitiveAndNormalised() {
        assertEquals("Memes", FolderRules.ruleFor("pictures/whatsapp/", rules))
        assertEquals("Memes", FolderRules.ruleFor("/Pictures/WhatsApp", rules))
        assertEquals("Memes", FolderRules.ruleFor("Pictures/WhatsApp/", mapOf("pictures/whatsapp" to "Memes")))
    }

    @Test fun assignmentBeatsRuleBeatsDefault() {
        assertEquals("Dog", FolderRules.desired("Pictures/WhatsApp/", "Dog", rules))
        assertEquals("Memes", FolderRules.desired("Pictures/WhatsApp/", null, rules))
        assertNull(FolderRules.desired("DCIM/Camera/", null, rules))
    }

    /** M2: a ".Bin" rule or hand-pick saved by v1.1.0 is not a folder: it counts as no rule. */
    @Test fun desiredIgnoresALegacyBinRuleOrHandPick() {
        val rules = mapOf("Pictures/" to "Memes", "Pictures/WhatsApp/" to ".bin", "DCIM/" to " .Bin ")
        assertEquals("Memes", FolderRules.desired("Pictures/WhatsApp/", null, rules))
        assertNull(FolderRules.desired("DCIM/Camera/", null, rules))
        assertEquals("Memes", FolderRules.desired("Pictures/WhatsApp/", ".BIN", rules))
        assertNull(FolderRules.desired("DCIM/Camera/", ".Bin", rules))
    }

    @Test fun canonicalSanitisesAndRejectsBlank() {
        assertEquals("a_b", FolderRules.canonical("  a/b. "))
        assertNull(FolderRules.canonical("   "))
        assertNull(FolderRules.canonical(""))
    }

    @Test fun binIsReserved() {
        assertNull(FolderRules.canonical(".Bin"))
        assertNull(FolderRules.canonical(".bin"))
        assertNull(FolderRules.canonical("  .BIN  "))
        assertNull(FolderRules.canonical(".Bin."))
        assertEquals("Bin", FolderRules.canonical("Bin"))
        assertEquals(".Binder", FolderRules.canonical(".Binder"))
    }

    @Test fun sameIgnoresCase() {
        assertTrue(FolderRules.same("Dog", "dOG"))
        assertTrue(!FolderRules.same("Dog", "Cat"))
    }
}
