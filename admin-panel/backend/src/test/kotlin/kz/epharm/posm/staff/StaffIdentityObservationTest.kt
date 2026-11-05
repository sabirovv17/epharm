package kz.epharm.posm.staff

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StaffIdentityObservationTest {
    private val iin="951212500015"
    private fun context(name:String?,status:String="available",iinStatus:String="missing") =
        StaffContext("u","s","w","p",standardNIinStatus=iinStatus,standardNFullName=name,standardNFullNameStatus=status)

    @Test fun `Russian Kazakh case whitespace and exact token order do not change comparison`() {
        for((registered,observed) in listOf(
            "Сабиров Амир" to "  АМИР\u00a0сабиров  ",
            "Сабиров\u0085Амир" to "АМиР Сабиров",
            "Әуезов Мұхтар Омарханұлы" to "\tМҰХТАР\u2003омарханұлы Әуезов\n",
            "Анна-Мария О'Нил" to "о'нил анна-мария",
        )) assertEquals(StaffIdentityMatch("NAME","MATCH"),StaffIdentityObservation.match(iin,registered,context(observed)))
    }

    @Test fun `every full token and its multiplicity must match without dropping patronymics`() {
        for(observed in listOf("Сабиров Амир Другой","Сабиров Другой","Сабиров Амир Амир","Сабирова Амир","Sabirov Amir"))
            assertEquals(StaffIdentityMatch("NAME","MISMATCH"),StaffIdentityObservation.match(iin,"Сабиров Амир",context(observed)))
        assertEquals(StaffIdentityMatch("NAME","MISMATCH"),StaffIdentityObservation.match(iin,"Сабиров Амир Амир",context("Сабиров Амир")))
    }

    @Test fun `initials generic login and partial names cannot be evidence even when equal`() {
        for(name in listOf("Сабиров А.","Сабиров А","Амир","Администратор Аптеки","Cashier User","Demo Person",
            "Employee Person","Пайдаланушы Дәріхана","касса-1 Амир","Амир@mail Сабиров","Сабиров_Амир","А.Б. Сабиров","Амир\u0000 Сабиров","Саб--иров Амир"))
            assertEquals(StaffIdentityMatch("NONE","UNKNOWN"),StaffIdentityObservation.match(iin,name,context(name)),name)
        assertEquals(StaffIdentityMatch("NONE","UNKNOWN"),StaffIdentityObservation.match(iin,"DEMO",context("Сабиров Амир")))
    }

    @Test fun `available IIN always wins and cannot be rescued by matching names`() {
        val c=context("Сабиров Амир").copy(standardNIin="000101500011",standardNIinStatus="available")
        assertEquals(StaffIdentityMatch("IIN","MISMATCH"),StaffIdentityObservation.match(iin,"Сабиров Амир",c))
        assertEquals("MISMATCH",StaffIinObservation.match(iin,c))
        assertEquals(StaffIdentityMatch("IIN","MATCH"),StaffIdentityObservation.match(iin,"Другое Имя",c.copy(standardNIin=iin)))
    }

    @Test fun `fallback is restricted to missing or unavailable IIN and a readable usable name`() {
        for(status in listOf("missing","unavailable")) {
            val c=context("Сабиров Амир",iinStatus=status)
            assertEquals(StaffIdentityMatch("NAME","MATCH"),StaffIdentityObservation.match(iin,"Сабиров Амир",c))
            assertEquals("UNKNOWN",StaffIinObservation.match(iin,c))
        }
        for(status in listOf("invalid","ambiguous"))
            assertEquals(StaffIdentityMatch("NONE","UNKNOWN"),StaffIdentityObservation.match(iin,"Сабиров Амир",context("Сабиров Амир",iinStatus=status)))
        for(status in listOf("missing","invalid","unavailable","ambiguous"))
            assertEquals(StaffIdentityMatch("NONE","UNKNOWN"),StaffIdentityObservation.match(iin,"Сабиров Амир",context(null,status)))
    }

    @Test fun `source status representation is bounded and legacy absent fields remain valid`() {
        assertTrue(StaffIinObservation.valid(StaffContext("u","s","w","p")))
        assertFalse(StaffIinObservation.valid(context(null)))
        assertFalse(StaffIinObservation.valid(context(" ")))
        assertFalse(StaffIinObservation.valid(context("Сабиров Амир","missing")))
        assertFalse(StaffIinObservation.valid(context("А".repeat(256))))
        assertFalse(StaffIinObservation.valid(context(null,"invented")))
        assertFalse(StaffIinObservation.valid(context("Амир\u0000 Сабиров")))
        assertFalse(StaffIinObservation.valid(context("Амир\u001cСабиров")))
    }
}
