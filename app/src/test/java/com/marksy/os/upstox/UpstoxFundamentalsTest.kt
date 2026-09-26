package com.marksy.os.upstox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpstoxFundamentalsTest {
    @Test fun keyRatiosReadCompanyAndSectorValues() {
        val r = UpstoxFundamentals.ratios("""{"status":"success","data":[{"name":"P/E","company_value":"20.15","sector_value":"12.46"},{"name":"ROE","company_value":"8.94%","sector_value":"16.46%"},{"name":"Quick ratio","company_value":"-","sector_value":null}]}""")
        assertEquals(listOf("P/E", "ROE", "Quick ratio"), r.map { it.name })
        assertEquals(20.15, r[0].companyValue!!, 1e-9)
        assertEquals(16.46, r[1].sectorValue!!, 1e-9)
        assertNull(r[2].companyValue)
        assertNull(r[2].sector)
    }

    @Test fun profileReadsMarketCapInCrore() {
        val p = UpstoxFundamentals.profile("""{"status":"success","data":{"company_profile":"Reliance Industries Limited is engaged in …","sector":"Refineries","sector_market_cap_inr":{"value":1942866.05,"unit":"crore","formatted":"1,942,866.05 Cr"}}}""")
        assertEquals("Refineries", p.sector)
        assertEquals(1942866.05, p.marketCapCr!!, 1e-6)
    }

    @Test fun statementsKeepPeriodsNewestFirst() {
        val s = UpstoxFundamentals.statement("""{"status":"success","data":{"type":"consolidated","time_period":"yearly","units_in":"crore","income_statement":[
            {"category":"revenue","history":[{"value":1086181,"period":"Mar 2026","change":"+10.53%"},{"value":982671,"period":"Mar 2025"}]},
            {"category":"net_profit","history":[{"value":95610,"period":"Mar 2026","change":"+18.35%"}]}]}}""", "income_statement")
        assertEquals(listOf("revenue", "net_profit"), s.map { it.category })
        assertEquals("Mar 2026", s[0].history.first().period)
        assertEquals("+10.53%", s[0].history.first().change)
        assertNull(s[0].history[1].change)
    }

    @Test fun balanceSheetAndShareholdingAndActions() {
        val b = UpstoxFundamentals.balance("""{"status":"success","data":{"history":[{"total_asset":1950121,"total_liability":940495,"period":"Mar 2025"}]}}""")
        assertEquals(1950121.0, b.single().totalAssets, 1e-9)
        val h = UpstoxFundamentals.shareholding("""{"status":"success","data":[{"category":"promoters","history":[{"period":"Mar 2026","value":50.0}]},{"category":"fii","history":[{"period":"Mar 2026","value":18.67}]}]}""")
        assertEquals(18.67, h[1].history.single().value, 1e-9)
        val a = UpstoxFundamentals.actions("""{"status":"success","data":[{"name":"Dividend","expiry_date":"14 Aug 2025","amount":5.5,"ratio":null,"event_details":[{"name":"Dividend type","value":"Final"}]}]}""")
        assertEquals(5.5, a.single().amount!!, 1e-9)
        assertNull(a.single().ratio)
        assertEquals("Dividend type" to "Final", a.single().details.single())
    }

    @Test fun newsIsReadForTheRequestedInstrument() {
        val n = UpstoxFundamentals.news("""{"status":"success","data":{"NSE_EQ|INE002A01018":[{"heading":"Reliance up","summary":"s","thumbnail":"t","article_link":"https://upstox.com/news/a","published_time":1776251261821}]},"metadata":{}}""", "NSE_EQ|INE002A01018")
        assertEquals("Reliance up", n.single().heading)
        assertEquals(1776251261821L, n.single().publishedAt)
    }

    @Test fun grahamNumberNeedsPositiveEarningsAndBook() {
        // EPS 100/20 = 5, BVPS 100/2 = 50 -> sqrt(22.5 * 5 * 50) = 75.
        assertEquals(75.0, UpstoxFundamentals.grahamNumber(100.0, pe = 20.0, pb = 2.0)!!, 1e-9)
        assertNull(UpstoxFundamentals.grahamNumber(100.0, pe = -8.0, pb = 2.0))
    }

    @Test fun dupontUsesTheLatestPeriodPresentInBothStatements() {
        val income = listOf(
            FinancialSeries("revenue", listOf(FinancialPeriod("Mar 2026", 1200.0, null), FinancialPeriod("Mar 2025", 1000.0, null))),
            FinancialSeries("net_profit", listOf(FinancialPeriod("Mar 2026", 120.0, null), FinancialPeriod("Mar 2025", 80.0, null)))
        )
        val balance = listOf(BalancePeriod("Mar 2025", totalAssets = 2000.0, totalLiabilities = 1000.0))
        val d = UpstoxFundamentals.dupont(income, balance)!!
        assertEquals("Mar 2025", d.period)
        assertEquals(.08, d.margin, 1e-9)
        assertEquals(.5, d.turnover, 1e-9)
        assertEquals(2.0, d.multiplier, 1e-9)
        assertEquals(.08, d.roe, 1e-9)
    }
}
