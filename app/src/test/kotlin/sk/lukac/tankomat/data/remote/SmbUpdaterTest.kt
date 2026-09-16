package sk.lukac.tankomat.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class SmbUpdaterTest {

    @Test
    fun `paths use backslashes and empty folder means share root`() {
        assertEquals("tankomat.apk", SmbUpdater.joinPath("", "tankomat.apk"))
        assertEquals("Tankomat\\tankomat.apk", SmbUpdater.joinPath("Tankomat", "tankomat.apk"))
        assertEquals("Apps\\Tankomat\\t.apk", SmbUpdater.joinPath("/Apps/Tankomat/", "t.apk"))
    }
}
