package kz.epharm.posm.staff

import java.util.Locale

internal data class StaffIdentityMatch(val method: String, val status: String)

/** Source names are weaker device observations, never account lookup or financial identity proof. */
internal object StaffIdentityObservation {
    private val statuses=setOf("available","missing","invalid","unavailable","ambiguous")
    private val spaces=Regex("(?U)\\s+")
    private val word=Regex("\\p{L}+(?:[-'’]\\p{L}+)*")
    private val generic=setOf("admin","administrator","manager","cashier","operator","user","test","demo","employee",
        "админ","администратор","менеджер","кассир","касса","оператор","пользователь","сотрудник","фармацевт",
        "аптека","тест","демо","әкімші","пайдаланушы","дәріхана","сынақ")

    // Match .NET Char.IsWhiteSpace, including NEL but excluding Java-only information separators.
    private fun whitespace(c:Char)=c in '\u0009'..'\u000D' || c=='\u0085' || Character.isSpaceChar(c)

    fun validNameContext(context: StaffContext): Boolean = context.standardNFullNameStatus in statuses &&
        if(context.standardNFullNameStatus=="available") context.standardNFullName?.let {
            it.length in 1..255 && it.any { c -> !whitespace(c) } &&
                it.none { c -> c.isISOControl() && !whitespace(c) }
        }==true else context.standardNFullName==null

    private fun tokens(value: String): List<String>? {
        if(value.length !in 1..255 || value.any { it.isISOControl() && !whitespace(it) }) return null
        val parts=value.trim(::whitespace).lowercase(Locale.ROOT).split(spaces)
        if(parts.size<2 || parts.any { !word.matches(it) || it.count(Char::isLetter)<2 || it in generic }) return null
        return parts.sorted()
    }

    fun match(iin: String, name: String, context: StaffContext): StaffIdentityMatch {
        if(!StaffIinObservation.valid(context)) return StaffIdentityMatch("NONE","UNKNOWN")
        if(context.standardNIinStatus=="available") return StaffIdentityMatch("IIN",StaffIinObservation.match(iin,context))
        if(context.standardNIinStatus !in setOf("missing","unavailable") ||
            context.standardNFullNameStatus!="available") return StaffIdentityMatch("NONE","UNKNOWN")
        val observed=context.standardNFullName?.let(::tokens) ?: return StaffIdentityMatch("NONE","UNKNOWN")
        val registered=tokens(name) ?: return StaffIdentityMatch("NONE","UNKNOWN")
        return StaffIdentityMatch("NAME",if(observed==registered) "MATCH" else "MISMATCH")
    }
}
