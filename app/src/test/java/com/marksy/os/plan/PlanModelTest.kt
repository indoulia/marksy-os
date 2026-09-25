package com.marksy.os.plan

import com.marksy.os.data.local.PlanItemEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PlanModelTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(m: Int, d: Int, h: Int = 9) = ZonedDateTime.of(2026, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val now = at(9, 25, 16)
    private fun item(id: Long, dueAt: Long?, status: PlanStatus = PlanStatus.TODO, completedAt: Long? = null) = PlanItemEntity(
        id = id, kind = PlanKind.BILL.name, title = "t$id", dueAt = dueAt, recurrence = "NONE", status = status.name,
        origin = "MANUAL", createdAt = 0, updatedAt = 0, completedAt = completedAt
    )

    @Test fun remindersGroupByDueWindowAndSkipUndatedTasks() {
        val items = listOf(
            item(1, at(10, 6)), item(2, at(9, 24)), item(3, at(9, 25)), item(4, at(10, 1)), item(5, null),
            item(6, at(9, 20), PlanStatus.DONE, completedAt = 10), item(7, at(9, 21), PlanStatus.DONE, completedAt = 20)
        )
        val g = PlanModel.reminders(items, now, zone)
        assertEquals(listOf(2L), g.overdue.map { it.id })
        assertEquals(listOf(3L, 4L), g.next7.map { it.id })
        assertEquals(listOf(1L), g.later.map { it.id })
        assertEquals(listOf(7L, 6L), g.done.map { it.id })
        assertEquals(listOf(2L, 3L, 4L, 1L), PlanModel.upcoming(items, 4).map { it.id })
    }

    @Test fun boardColumnsHoldEveryItemDatedFirst() {
        val items = listOf(item(1, null), item(2, at(10, 6)), item(3, at(9, 26), PlanStatus.DOING), item(4, at(9, 1), PlanStatus.DONE))
        assertEquals(listOf(2L, 1L), PlanModel.column(items, PlanStatus.TODO).map { it.id })
        assertEquals(listOf(3L), PlanModel.column(items, PlanStatus.DOING).map { it.id })
        assertEquals(listOf(4L), PlanModel.column(items, PlanStatus.DONE).map { it.id })
    }
}
