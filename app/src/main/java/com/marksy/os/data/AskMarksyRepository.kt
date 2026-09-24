package com.marksy.os.data

import com.marksy.os.data.local.ContextEntity
import com.marksy.os.data.local.ContextGraphDao
import com.marksy.os.data.local.NotificationEventDao
import com.marksy.os.data.local.NotificationEventEntity
import com.marksy.os.intelligence.AskMarksy
import java.time.ZoneId

/** Room-backed [AskMarksy.Retriever] plus the interpreter chain with deterministic fallback. */
class AskMarksyRepository(
    private val eventDao: NotificationEventDao,
    private val graphDao: ContextGraphDao,
    private val interpreters: List<AskMarksy.QueryInterpreter> = emptyList(),
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis
) : AskMarksy.Retriever {
    override suspend fun events(from: Long, to: Long, limit: Int): List<NotificationEventEntity> = eventDao.findInRange(from, to, limit)
    override suspend fun entities(name: String): List<ContextEntity> = graphDao.search(name, 10)
    override suspend fun eventIdsFor(entityId: Long): List<Long> = graphDao.eventIdsFor(entityId)

    suspend fun ask(text: String, previous: AskMarksy.Query?): AskMarksy.Answer =
        AskMarksy.ask(text, previous, interpreters, this, clock(), zone())
}
