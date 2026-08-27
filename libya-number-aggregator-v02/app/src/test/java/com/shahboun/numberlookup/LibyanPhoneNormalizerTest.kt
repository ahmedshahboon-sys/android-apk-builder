package com.shahboun.numberlookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibyanPhoneNormalizerTest {
    @Test fun localFormatNormalizesToE164() {
        val n = LibyanPhoneNormalizer.normalize("0912345678")
        assertTrue(n.isValidFormat)
        assertEquals("0912345678", n.localNumber)
        assertEquals("+218912345678", n.e164Number)
    }

    @Test fun plus218MatchesLocalFormat() {
        val a = LibyanPhoneNormalizer.normalize("0912345678")
        val b = LibyanPhoneNormalizer.normalize("+218912345678")
        assertEquals(a.e164Number, b.e164Number)
        assertEquals(a.localNumber, b.localNumber)
    }

    @Test fun doubleZero218MatchesLocalFormat() {
        val a = LibyanPhoneNormalizer.normalize("0912345678")
        val b = LibyanPhoneNormalizer.normalize("00218912345678")
        assertEquals(a.e164Number, b.e164Number)
        assertEquals(a.localNumber, b.localNumber)
    }

    @Test fun invalidNumberIsRejected() {
        val n = LibyanPhoneNormalizer.normalize("12345")
        assertFalse(n.isValidFormat)
    }

    @Test fun supportedLibyanPrefixesAreAccepted() {
        listOf("0912345678", "0922345678", "0932345678", "0942345678").forEach {
            assertTrue("Expected $it to be valid", LibyanPhoneNormalizer.normalize(it).isValidFormat)
        }
    }
}
