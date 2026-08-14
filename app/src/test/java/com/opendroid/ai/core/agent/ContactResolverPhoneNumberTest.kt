package com.opendroid.ai.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContactResolver.isPhoneNumber] decides whether a request like "call 5551234567" is a
 * literal number or a contact name to look up. Getting it wrong in the permissive
 * direction is the expensive case: a name misread as a number is dialled verbatim
 * instead of being resolved against contacts.
 */
class ContactResolverPhoneNumberTest {

    @Test
    fun `plain and formatted numbers are recognised`() {
        assertTrue(ContactResolver.isPhoneNumber("5551234567"))
        assertTrue(ContactResolver.isPhoneNumber("555-123-4567"))
        assertTrue(ContactResolver.isPhoneNumber("(555) 123-4567"))
        assertTrue(ContactResolver.isPhoneNumber("555.123.4567"))
        assertTrue(ContactResolver.isPhoneNumber("  5551234567  "))
    }

    @Test
    fun `a single leading plus is accepted for international numbers`() {
        assertTrue(ContactResolver.isPhoneNumber("+15551234567"))
        assertTrue(ContactResolver.isPhoneNumber("+91 98765 43210"))
    }

    @Test
    fun `letters disqualify the input instead of being stripped out of it`() {
        // The earlier implementation removed every non-digit before measuring, so these
        // reached the dialler as bare digit strings.
        assertFalse(ContactResolver.isPhoneNumber("abc1234567"))
        assertFalse(ContactResolver.isPhoneNumber("Rob 2nd phone 1234567"))
        assertFalse(ContactResolver.isPhoneNumber("call1234567now"))
    }

    @Test
    fun `a plus anywhere but the front is rejected`() {
        assertFalse(ContactResolver.isPhoneNumber("12+345+6"))
        assertFalse(ContactResolver.isPhoneNumber("5551234+567"))
        assertFalse(ContactResolver.isPhoneNumber("++15551234567"))
    }

    @Test
    fun `too few digits is a name, not a number`() {
        assertFalse(ContactResolver.isPhoneNumber("123456"))
        assertFalse(ContactResolver.isPhoneNumber("+123456"))
        assertFalse(ContactResolver.isPhoneNumber(""))
        assertFalse(ContactResolver.isPhoneNumber("   "))
        assertFalse(ContactResolver.isPhoneNumber("+"))
    }
}
