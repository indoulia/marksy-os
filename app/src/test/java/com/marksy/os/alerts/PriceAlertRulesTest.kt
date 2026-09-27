package com.marksy.os.alerts

import org.junit.Assert.assertEquals
import org.junit.Test

class PriceAlertRulesTest {
    private fun alert(id: Long, symbol: String, price: Double, above: Boolean) = PriceAlert(id, symbol, price, above, 0L)

    @Test
    fun anAlertFiresOnlyOnceItsLevelIsCrossedInItsDirection() {
        val alerts = listOf(alert(1, "INFY", 1500.0, above = true), alert(2, "INFY", 1400.0, above = false), alert(3, "TCS", 3000.0, above = true))

        val fired = PriceAlertRules.triggered(alerts, mapOf("INFY" to 1500.0, "TCS" to 2999.5))

        assertEquals(listOf(1L), fired.map { it.first.id })
        assertEquals(listOf(2L), PriceAlertRules.triggered(alerts, mapOf("INFY" to 1399.0)).map { it.first.id })
    }

    @Test
    fun directionComesFromWhereTheLevelSitsAgainstThePrice() {
        assertEquals(true, PriceAlertRules.above(target = 110.0, lastPrice = 100.0))
        assertEquals(false, PriceAlertRules.above(target = 90.0, lastPrice = 100.0))
    }
}
