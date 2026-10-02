package com.marksy.os.portfolio

import com.marksy.os.upstox.UpstoxAuthException
import com.marksy.os.upstox.UpstoxHolding
import java.io.IOException

/** Upstox holdings fetched with today's OAuth token; [load] is the api.upstox.com call. */
class UpstoxHoldingsSource(
    private val token: () -> String?,
    private val load: suspend (token: String) -> List<UpstoxHolding>
) : HoldingsSource {
    override suspend fun fetch(now: Long): HoldingsResult {
        val bearer = token() ?: return HoldingsResult.SignedOut("Upstox ends every sign-in at 3:30 am")
        return try {
            HoldingsResult.Ok(HoldingsSnapshot(PortfolioProviders.UPSTOX, load(bearer).mapNotNull(::toHolding), now))
        } catch (e: UpstoxAuthException) {
            HoldingsResult.SignedOut("Upstox ended the sign-in")
        } catch (e: IOException) {
            HoldingsResult.Failed(e.message ?: "Couldn't reach Upstox")
        }
    }

    companion object {
        private val ETF_WORD = Regex("\\bETF\\b", RegexOption.IGNORE_CASE)

        fun toHolding(u: UpstoxHolding): Holding? {
            if (u.quantity <= 0) return null
            val price = u.lastPrice.takeIf { it > 0 } ?: u.closePrice ?: u.averagePrice
            val type = if (isEtf(u.tradingSymbol, u.companyName)) HoldingType.ETF else HoldingType.STOCK
            return Holding(u.tradingSymbol.uppercase(), u.companyName, u.isin, u.instrumentToken, type, u.quantity, u.averagePrice, price, u.closePrice)
        }

        fun isEtf(symbol: String, name: String): Boolean =
            symbol.uppercase().let { it.endsWith("BEES") || it.endsWith("ETF") } || ETF_WORD.containsMatchIn(name)
    }
}
