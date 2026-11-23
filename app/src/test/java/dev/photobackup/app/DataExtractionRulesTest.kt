package dev.photobackup.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.photobackup.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataExtractionRulesTest {
    private val domains = setOf(
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref",
    )

    /** Nothing (ledger, settings, encrypted password) goes to cloud backup or to a new phone. */
    @Test fun everyDomainIsExcludedFromCloudBackupAndDeviceTransfer() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val parser = ctx.resources.getXml(R.xml.data_extraction_rules)
        val excluded = mutableMapOf<String, MutableSet<String>>()
        var section: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "cloud-backup", "device-transfer" -> section = parser.name
                "include" -> throw AssertionError("nothing may be included")
                "exclude" -> {
                    assertEquals(".", parser.getAttributeValue(null, "path"))
                    excluded.getOrPut(section!!) { mutableSetOf() } += parser.getAttributeValue(null, "domain")
                }
            }
        }
        assertEquals(domains, excluded["cloud-backup"])
        assertEquals(domains, excluded["device-transfer"])
    }
}
