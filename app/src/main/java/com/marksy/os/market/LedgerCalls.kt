package com.marksy.os.market

import java.util.Locale

/** How a ledger tip reads on the phone. Every state, outcome and return is the server's (spec §6.6, §7); this only
 * words it, so a withdrawn losing Marksy call reads as a failed exit, never as a neutral invalidation. */
object LedgerCalls {
    fun returnText(fraction: Double?): String? = fraction?.let { String.format(Locale.US, "%+.2f%%", it * 100) }
}
