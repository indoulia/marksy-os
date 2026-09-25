package com.marksy.os.plan

enum class PlanKind(val label: String) {
    BILL("Bill"), EMI("EMI"), CARD_DUE("Card bill"), BIRTHDAY("Birthday"), TASK("Task"), FOLLOW_UP("Follow-up")
}

enum class PlanStatus(val label: String) { TODO("To do"), DOING("Doing"), DONE("Done") }

enum class Recurrence(val label: String) { NONE("Once"), MONTHLY("Monthly"), YEARLY("Yearly") }

/** Where an item came from; auto-created ones are updated in place by their dedupe key. */
enum class PlanOrigin { SMS, CONTACTS, MANUAL, REMIND_ME }

/** A due found in a notification: what, when (09:00 local on the due day), how much, to whom. */
data class DueNotice(val kind: PlanKind, val dueAt: Long, val amountMinor: Long?, val counterparty: String?)
