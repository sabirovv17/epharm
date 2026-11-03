package kz.epharm.posm.staff

/** A device observation is diagnostic only; it cannot assign a financial seller identity. */
internal object StaffIinObservation {
    private val statuses=setOf("available","missing","invalid","unavailable","ambiguous")
    private val digits=Regex("[0-9]{12}")

    fun valid(context: StaffContext): Boolean = context.standardNIinStatus in statuses &&
        if(context.standardNIinStatus=="available") context.standardNIin?.let(digits::matches)==true
        else context.standardNIin==null

    fun match(iin: String, context: StaffContext): String = when {
        context.standardNIinStatus!="available" || !valid(context) -> "UNKNOWN"
        context.standardNIin==iin -> "MATCH"
        else -> "MISMATCH"
    }
}
