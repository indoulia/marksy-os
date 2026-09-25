package com.marksy.os.plan

import com.marksy.os.data.local.PlanItemEntity
import java.time.Instant
import java.time.ZoneId

/** Views over plan items: the Reminders list (dated only), Home's upcoming strip and the board's columns. */
object PlanModel {
    data class Groups(
        val overdue: List<PlanItemEntity>,
        val next7: List<PlanItemEntity>,
        val later: List<PlanItemEntity>,
        val done: List<PlanItemEntity>
    )

    fun reminders(items: List<PlanItemEntity>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Groups {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val weekEnd = Instant.ofEpochMilli(today).atZone(zone).plusDays(7).toInstant().toEpochMilli()
        val active = activeDated(items)
        return Groups(
            overdue = active.filter { it.dueAt!! < today },
            next7 = active.filter { it.dueAt!! in today until weekEnd },
            later = active.filter { it.dueAt!! >= weekEnd },
            done = items.filter { it.status == PlanStatus.DONE.name && it.dueAt != null }.sortedByDescending { it.completedAt ?: 0 }
        )
    }

    fun upcoming(items: List<PlanItemEntity>, limit: Int): List<PlanItemEntity> = activeDated(items).take(limit)

    fun column(items: List<PlanItemEntity>, status: PlanStatus): List<PlanItemEntity> =
        items.filter { it.status == status.name }.sortedWith(compareBy({ it.dueAt == null }, { it.dueAt }, { it.id }))

    private fun activeDated(items: List<PlanItemEntity>) =
        items.filter { it.status != PlanStatus.DONE.name && it.dueAt != null }.sortedBy { it.dueAt }
}
