package com.marksy.os.intelligence

import com.marksy.os.data.local.NotificationEventEntity

/**
 * EPIC-014 action discovery. Pure: offers only actions the event and the device can genuinely
 * support. Marksy never performs external side effects (payments, orders, tracking APIs) itself;
 * "track"/"order"/"payment" hand off to the installed source app, where the user acts.
 */
object ActionEngine {
    enum class Type(val label: String) {
        OPEN_SOURCE("Open app"),
        TRACK("Track in app"),
        ORDER("View order in app"),
        PAYMENT("Pay in app"),
        REMIND("Remind me"),
        MARK_EXPECTED("Mark expected"),
        MARK_RESOLVED("Mark resolved"),
        REPORT("Report wrong category"),
        IGNORE("Ignore")
    }

    /** Device capabilities, injected so discovery stays testable and honest about what can run. */
    interface Capabilities {
        fun canLaunch(sourcePackage: String): Boolean
        fun canPostNotifications(): Boolean
    }

    data class Available(val type: Type, val enabled: Boolean, val reason: String)

    fun discover(event: NotificationEventEntity, capabilities: Capabilities, nowMillis: Long = System.currentTimeMillis()): List<Available> {
        val facts = EventNormalizer.factsFromJson(event.intelligenceJson)
        val launchable = capabilities.canLaunch(event.sourcePackage)
        val open = event.lifecycleState != EventLifecycle.State.RESOLVED.name && !event.archived
        val appName = event.sourceName.ifBlank { event.sourcePackage }
        val out = mutableListOf<Available>()

        if (launchable) out += Available(Type.OPEN_SOURCE, true, "Opens $appName")

        val tracking = facts.references.firstOrNull { it.type == EventExtractor.ReferenceType.TRACKING }
        val order = facts.references.firstOrNull { it.type == EventExtractor.ReferenceType.ORDER }
        if (event.category == "DELIVERY" && (tracking != null || order != null) && launchable && open && !facts.terminal) {
            out += Available(Type.TRACK, true, "Opens $appName for ${(tracking ?: order)!!.value}")
        }
        if (order != null && launchable && event.category != "DELIVERY") {
            out += Available(Type.ORDER, true, "Opens $appName for order ${order.value}")
        }
        if (event.category == "BILLS" && open && launchable) {
            out += Available(Type.PAYMENT, true, "Opens $appName to pay; Marksy never pays on your behalf")
        }

        if (open) {
            out += if (capabilities.canPostNotifications()) Available(Type.REMIND, true, "Local reminder from Marksy")
            else Available(Type.REMIND, false, "Allow Marksy notifications to use reminders")
        }
        val future = facts.times.firstOrNull { it.epochMillis > nowMillis }
        if (open && event.category in EXPECTABLE && !facts.terminal) {
            out += Available(Type.MARK_EXPECTED, true, future?.let { "Expected \"${it.raw}\"" } ?: "Track as something you are waiting for")
        }
        if (open) out += Available(Type.MARK_RESOLVED, true, "Moves the thread to Resolved")
        out += Available(Type.REPORT, true, "Records a classification correction (stays on device)")
        if (!event.archived) out += Available(Type.IGNORE, true, "Archives it and teaches Marksy it was not useful")
        return out
    }

    private val EXPECTABLE = setOf("DELIVERY", "PAYMENTS", "BANKING", "BILLS")
}
