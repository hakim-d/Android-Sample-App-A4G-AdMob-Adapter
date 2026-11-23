package com.hakim_ma.a4g

import com.hakim_ma.a4g.data.Validator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatorTest {
    @Test fun validAndEmptyUnitsPass() {
        assertNull(Validator.adUnitError("ca-app-pub-1234567890123456/1234567890"))
        assertNull(Validator.adUnitError("  ca-app-pub-1234567890123456/1234567890 "))
        assertNull(Validator.adUnitError(""))
    }
    @Test fun appIdPastedAsUnit() =
        assertTrue(Validator.adUnitError("ca-app-pub-1234567890123456~1234567890")!!.startsWith("That's an App ID"))
    @Test fun ad4gameParameterPastedAsUnit() =
        assertTrue(Validator.adUnitError("/12345678,87654321/demo_banner")!!.contains("Ad4Game Parameter"))
    @Test fun unitPastedAsAppId() =
        assertTrue(Validator.appIdError("ca-app-pub-1234567890123456/1234567890")!!.startsWith("That's an ad unit"))
    @Test fun publisherMismatch() {
        assertEquals("1111111111111111" to "2222222222222222",
            Validator.publisherMismatch("ca-app-pub-1111111111111111~1234567890", "ca-app-pub-2222222222222222/1234567890"))
        assertNull(Validator.publisherMismatch("ca-app-pub-1111111111111111~1234567890", "ca-app-pub-1111111111111111/1234567890"))
    }
}
