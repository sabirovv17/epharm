package kz.epharm.merch.automation

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class WeeklyCycle(val number:Int,val startsAt:Instant,val dueAt:Instant,val nextAt:Instant?,val preparation:Boolean)

/** Source 062 initial generation + 074 calendar SLA + 075 period boundary; seven calendar days, not business days. */
object WeeklyCycleCalendar {
    private val KZ=ZoneId.of("Asia/Almaty")
    fun end(month:LocalDate):Instant=month.plusMonths(1).atStartOfDay(KZ).toInstant()
    fun first(month:LocalDate,approvedAt:Instant?,now:Instant):WeeklyCycle? {
        require(month.dayOfMonth==1)
        val start=month.atStartOfDay(KZ).toInstant();val end=end(month)
        if(!now.isBefore(end)) return null
        val anchor=maxOf(start,approvedAt?:now)
        if(!anchor.isBefore(end)) return null
        return if(now.isBefore(start)) WeeklyCycle(1,now,start,start,true)
        else scheduled(1,anchor,end)
    }
    fun next(month:LocalDate,previous:WeeklyCycle,now:Instant):WeeklyCycle? {
        val end=end(month);val start=previous.nextAt?:return null
        if(!now.isBefore(end) || start.isAfter(now) || !start.isBefore(end)) return null
        return scheduled(Math.addExact(previous.number,1),start,end)
    }
    private fun scheduled(number:Int,start:Instant,end:Instant):WeeklyCycle {
        val due=minOf(start.plusSeconds(7*86400L),end)
        return WeeklyCycle(number,start,due,due.takeIf {it.isBefore(end)},false)
    }
}
