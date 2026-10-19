package com.marksy.os.upstox

/** Where the sandbox token stands; expired or missing turns practice orders off, never live ones on. */
data class UpstoxSandboxStatus(val state: State, val expiresAt: Long?, val daysLeft: Int) {
    enum class State { NONE, VALID, EXPIRING, EXPIRED }

    companion object {
        const val WARN_DAYS = 3
        private const val DAY_MS = 24L * 60 * 60 * 1000

        fun of(expiresAt: Long?, now: Long): UpstoxSandboxStatus {
            if (expiresAt == null) return UpstoxSandboxStatus(State.NONE, null, 0)
            if (now >= expiresAt) return UpstoxSandboxStatus(State.EXPIRED, expiresAt, 0)
            val days = ((expiresAt - now + DAY_MS - 1) / DAY_MS).toInt()
            return UpstoxSandboxStatus(if (days <= WARN_DAYS) State.EXPIRING else State.VALID, expiresAt, days)
        }
    }
}
