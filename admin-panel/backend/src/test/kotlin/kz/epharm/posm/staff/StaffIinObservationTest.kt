package kz.epharm.posm.staff

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StaffIinObservationTest {
    private val context=StaffContext("cashier","session","workstation","process")
    private val iin="951212500015"

    @Test fun `source absence is never a mismatch`() {
        for(status in listOf("missing","invalid","unavailable","ambiguous")) {
            val c=context.copy(standardNIinStatus=status)
            assertTrue(StaffIinObservation.valid(c))
            assertEquals("UNKNOWN",StaffIinObservation.match(iin,c))
        }
    }
    @Test fun `comparison requires one available exact source IIN`() {
        assertEquals("MATCH",StaffIinObservation.match(iin,context.copy(standardNIin=iin,standardNIinStatus="available")))
        assertEquals("MISMATCH",StaffIinObservation.match(iin,context.copy(standardNIin="000101500011",standardNIinStatus="available")))
        assertFalse(StaffIinObservation.valid(context.copy(standardNIinStatus="available")))
        assertFalse(StaffIinObservation.valid(context.copy(standardNIin=iin)))
        assertFalse(StaffIinObservation.valid(context.copy(standardNIin="９５１２１２５０００１５",standardNIinStatus="available")))
        assertFalse(StaffIinObservation.valid(context.copy(standardNIinStatus="invented")))
    }
}
