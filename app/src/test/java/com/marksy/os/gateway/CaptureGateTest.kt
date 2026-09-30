package com.marksy.os.gateway

import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CaptureGateTest {
    private val context = CaptureContext(
        capturePackages = setOf("com.upstox.pro"),
        chatAllowList = setOf("stocktips", "rahul", "+91 98765 43210", "zerodh"),
        chatSenders = setOf("Rahul", "Amit"),
        username = "prsingh",
        deviceSalt = "salt-1"
    )

    @Test
    fun anAppNotificationSendsTheAppNameAndItsCleanedTextOnly() {
        val sent = send(event(title = "RENUKA call for prsingh", body = "BUY RENUKA CMP 23.62 SL 22.25 TGT 26. Call 9876543210"))

        assertEquals(
            setOf("deviceEventKey", "medium", "appPackage", "channelLabel", "text", "devicePostedAt"),
            sent.toJson().keys().asSequence().toSet()
        )
        assertEquals("APP_NOTIFICATION" to "Upstox", sent.medium to sent.channelLabel)
        assertEquals("RENUKA call for [USER]\nBUY RENUKA CMP 23.62 SL 22.25 TGT 26. Call [PHONE]", sent.text)
        assertEquals("1970-01-01T00:00:02Z", sent.devicePostedAt)
    }

    @Test
    fun aGroupMessageSendsTheGroupNameAndNoSenderName() {
        val whatsapp = send(event(
            pkg = "com.whatsapp", source = "WhatsApp", title = "StockTips: Rahul", group = true,
            body = "Rahul: BUY RENUKA CMP 23.62 SL 22.25 TGT 26\nAmit: thanks Rahul"
        ))
        assertEquals("WHATSAPP" to "StockTips", whatsapp.medium to whatsapp.channelLabel)
        assertEquals("BUY RENUKA CMP 23.62 SL 22.25 TGT 26\nthanks [SENDER]", whatsapp.text)

        // I3 fix: the body must actually contain the sender's name, or assertFalse below can never fail.
        val telegramContext = context.copy(chatSenders = setOf("Rahul Sharma"))
        val telegram = (CaptureGate.decide(
            event(
                pkg = "org.telegram.messenger", source = "Telegram", title = "Rahul Sharma @ StockTips", group = true,
                body = "Rahul Sharma: BUY RENUKA CMP 23.62 SL 22.25 TGT 26"
            ),
            telegramContext
        ) as CaptureDecision.Send).message
        assertEquals("TELEGRAM" to "StockTips", telegram.medium to telegram.channelLabel)
        assertFalse(telegram.text.contains("Rahul"))

        // 4b pre-merge: a "Name SL: " prefix is a sender, not a level; a level prefix is spared only after nothing, emoji or a side word.
        fun group(body: String) = send(event(pkg = "com.whatsapp", source = "WhatsApp", title = "StockTips", group = true, body = body)).text
        assertEquals("1450 buy INFY, SL 1400 TGT 1500", group("Ravi SL: 1450 buy INFY, SL 1400 TGT 1500"))
        assertEquals("BUY RENUKA CMP : 23.62 SL : 22.25 TGT : 26", group("BUY RENUKA CMP : 23.62 SL : 22.25 TGT : 26"))
        assertEquals("BUY LCCPROJECT\n🎯 Target : Rs 173.08\nEntry: Rs 144.24", group("BUY LCCPROJECT\n🎯 Target : Rs 173.08\nEntry: Rs 144.24"))
    }

    @Test
    fun everyKnownSenderIsMaskedRegardlessOfPresenceInTheRow() {
        // Finding C3: mask every known chatSenders name, not only the ones this row itself names.
        val withBothSenders = context.copy(chatSenders = setOf("Amit", "Rahul"))
        val whatsapp = (CaptureGate.decide(
            event(
                pkg = "com.whatsapp", source = "WhatsApp", title = "StockTips: Amit", group = true,
                body = "Amit: Rahul's BUY RENUKA CMP 23 SL 22 TGT 26"
            ),
            withBothSenders
        ) as CaptureDecision.Send).message
        assertFalse(whatsapp.text.contains("Amit"))
        assertFalse(whatsapp.text.contains("Rahul"))

        // A short handle ("RK", length 2) must still be masked (MIN_MASKED_SENDER_LENGTH lowered to 2).
        val withShortHandle = context.copy(chatSenders = setOf("Amit", "RK"))
        val telegram = (CaptureGate.decide(
            event(
                pkg = "org.telegram.messenger", source = "Telegram", title = "StockTips", group = true,
                body = "Amit: BUY RENUKA CMP 23 SL 22 TGT 26, thanks RK"
            ),
            withShortHandle
        ) as CaptureDecision.Send).message
        assertFalse(telegram.text.contains("RK"))
    }

    @Test
    fun anAllowListedOneToOneChatIsNeverSent() {
        fun decide(pkg: String, title: String, group: Boolean?) =
            CaptureGate.decide(event(pkg = pkg, source = "Chat", title = title, group = group), context)
        val oneToOne = CaptureDecision.Keep(CaptureGate.ONE_TO_ONE_CHAT)

        assertEquals(oneToOne, decide("com.whatsapp", "Rahul", false))
        assertEquals(oneToOne, decide("com.whatsapp", "+91 98765 43210", false))
        assertEquals(oneToOne, decide("org.telegram.messenger", "Rahul", false))
        assertEquals(CaptureDecision.Keep(CaptureGate.GROUP_UNKNOWN), decide("com.whatsapp", "Rahul", null))
        assertTrue(decide("com.whatsapp", "StockTips", true) is CaptureDecision.Send)
    }

    // R3: every case uses the neutral title "Upstox" -- own-order evidence must come from the body alone.
    @Test
    fun theCustomersOwnOrderNotificationsNeverLeaveThePhone() {
        val ownOrder = CaptureDecision.Keep(CaptureGate.OWN_ORDER)
        val neutralTitle = "Upstox"
        listOf(
            "order executed" to "Your order to BUY 10 RELIANCE is executed",
            "order filled" to "BUY 10 TCS filled at 3900",
            "order placed" to "Your SELL order for 5 INFY has been placed",
            "order cancelled" to "Your BUY order for 5 INFY was cancelled",
            "order rejected (insufficient margin)" to "RMS: insufficient margin for RELIANCE",
            "gtt triggered" to "Your GTT for RELIANCE has been triggered",
            "order executed with price" to "Your BUY order for 10 RELIANCE @ 1450.50 is executed",
            "order placed with rupee note" to "Your SELL order for 5 INFY at Rs. 1500 has been placed",
            "order traded with avg price" to "BUY order traded: 10 RELIANCE, average price 1450",
            "order successful with qty avg price" to "Your Buy Order for RELIANCE is Successful. Qty 10, Avg Price 1450.50",
            "order open at exchange" to "Your SELL order for 5 INFY is open at exchange",
            "position opened" to "Position opened: BUY 10 RELIANCE @ 1450",
            "stop loss order hit with qty" to "Stop loss order for RELIANCE hit at 1420.50, qty 10",
            "trades executed" to "Trades executed for your account",
            "order no. executed" to "Order No. 2026093012345: RELIANCE BUY 10 Executed",
            "executed at symbol" to "executed @ 1450",
            "bought shares" to "Bought 10 shares of RELIANCE",
            "sip processed" to "SIP of Rs. 5000 in XYZ processed",
            "trade confirmation with bought" to "Trade confirmation: Bought 10 RELIANCE @ 1450",
            "own order that also reads like a call" to "Your BUY order for 10 RELIANCE is executed at 1450. Target 1500, SL 1420",
            "bracket order placed" to "Your bracket order to BUY 10 RELIANCE at 1450 has been placed. Target 1500, Stoploss 1420",
            "gtt triggered with levels" to "Your GTT for RELIANCE has been triggered: BUY 10 @ 1450, target 1500, SL 1420",
            "order pending with levels" to "Your SELL order for 5 INFY at 1500 is pending. SL 1520, Target 1450",
            "order executed with full description" to "Your BUY order for 10 shares of RELIANCE INDUSTRIES LTD (NSE, CNC, LIMIT @ 1450.00, Target 1500, SL 1420, validity DAY) has been executed",
            // R1: inflected status words ("submitted"/"opened"/"successfully") must still count as own-order evidence.
            "order successfully submitted" to "Your BUY order for RELIANCE was successfully submitted",
            "order submitted successfully" to "Your SELL order request for INFY submitted successfully",
            "position has been opened" to "Your position has been opened. Stop loss 1420",
            // R2: a possessive order phrase alone is strong evidence; Hinglish and Devanagari spellings too.
            "order sent to exchange" to "Your BUY order for 10 RELIANCE has been sent to exchange",
            "gtt is active" to "Your GTT for BUY 10 RELIANCE is active. Target 1500, Stop loss 1420",
            "hinglish possessive order" to "Aapka BUY order RELIANCE ke liye execute ho gaya",
            "devanagari possessive order" to "आपका BUY ऑर्डर RELIANCE execute हो गया",
            "order submitted, no marker" to "BUY order for 10 RELIANCE submitted",
            "devanagari oblique possessive" to "आपके BUY ऑर्डर RELIANCE execute किए गए. Target 1500, SL 1420",
            "devanagari alternate spelling" to "आपका BUY आर्डर RELIANCE पूरा हुआ",
            "hinglish oblique possessive" to "Aapke BUY order RELIANCE ke liye placed. Target 1500, SL 1420",
            "plural positions" to "Your positions: BUY 10 RELIANCE average price 1450, SL 1420, Target 1500"
        ).forEach { (description, body) -> assertEquals(description, ownOrder, CaptureGate.decide(event(title = neutralTitle, body = body), context)) }

        val brokerSms = event(pkg = "com.google.android.apps.messaging", source = "Messages", title = "JD-ZERODH-S",
            body = "Your order to BUY 10 RELIANCE is executed at 1450")
        assertEquals(ownOrder, CaptureGate.decide(brokerSms, context))
        val researchCall = event(title = "Research call", body = "Buy order: RENUKA above 24, target 26, stop loss 22")
        assertTrue(CaptureGate.decide(researchCall, context) is CaptureDecision.Send)

        // 4b pre-merge: broker wording for GTT, OCO, bracket, cover, super and AMO orders, and a side with a quantity, is an own order.
        listOf(
            "GTT: BUY 10 INFY @ 1450, SL 1400, target 1500 created",
            "GTT created: BUY 10 INFY @ 1450 SL 1400 TGT 1500",
            "Forever OCO order created: SELL 5 TCS @ 3900 SL 3950 TGT 3800",
            "Super order created: BUY 10 INFY @ 1450 SL 1400 TGT 1500",
            "Bracket order: BUY 10 INFY @ 1450 SL 1400 TGT 1500 confirmed",
            "Buy order for 10 RELIANCE @ 1450 SL 1420 TGT 1500 confirmed",
            "Buy order for 10 RELIANCE @ 1450 SL 1420 TGT 1500 accepted",
            "BUY INFY 10 @ 1450 SL 1400 TGT 1500 order successful",
            "Cover order BUY 10 INFY @ 1450 SL 1400 TGT 1500 is open",
            "Order complete: BUY 10 INFY @ 1450, SL 1400, target 1500",
            "Executed: BUY 10 INFY @ 1450 SL 1400 TGT 1500",
            "Stop loss hit: SELL 10 RELIANCE @ 1420 (entry 1450)",
            "Target hit! SELL 10 INFY @ 1500, entry 1450",
            "Your AMO BUY INFY @ 1450 SL 1400 is queued"
        ).forEach { body ->
            val rows = listOf(
                event(title = "Upstox", body = body),
                event(pkg = "com.zerodha.kite3", source = "Zerodha", title = "Zerodha", body = body),
                event(pkg = "com.google.android.apps.messaging", source = "Messages", title = "JD-ZERODH-S", body = body).copy(category = "OTHER", isTrading = false)
            )
            rows.forEach { row ->
                assertEquals("${row.title}: $body", ownOrder, CaptureGate.decide(row, context))
                assertFalse("${row.title}: $body", CaptureGate.queues(row.sourcePackage, row.category, row.chatGroup, row.title, row.body))
            }
        }
    }

    @Test
    fun researchCallsStillGoButLevelFreeSourceExitsStayLocal() {
        val stillGo = listOf("BUY RENUKA CMP 23.62 SL 22.25 TGT 26")
        // 4b review C1(c)/I1: no side word with two priced levels, so this exit stays local from apps and groups alike (M6).
        val levelFreeExit = "Target 26 achieved. Trade completed, book profits"
        // These two now carry strong own-order evidence ("trade ... triggered", "order ... placed") on their own; see task-B3-report.md round 2.
        val nowOwnOrderToo = listOf(
            "Our RELIANCE trade: Stop loss triggered, exit now",
            "Buy order to be placed above 24, target 26, SL 22"
        )
        // A group row is never subject to the own-order check, so a call still sends regardless; a level-free exit does not.
        fun group(body: String) = CaptureGate.decide(event(pkg = "com.whatsapp", source = "WhatsApp", title = "StockTips", body = body, group = true), context)
        (stillGo + nowOwnOrderToo[1]).forEach { body -> assertTrue(body, group(body) is CaptureDecision.Send) }
        listOf(levelFreeExit, nowOwnOrderToo[0]).forEach { body -> assertEquals(body, CaptureDecision.Keep(CaptureGate.NOT_A_CANDIDATE), group(body)) }
        stillGo.forEach { body ->
            assertTrue(body, CaptureGate.decide(event(title = "Research call", body = body), context) is CaptureDecision.Send)
        }
        // 4b pre-merge: "trade ... completed" is own-order evidence now, so an app keeps this exit as an own order before the signal check.
        assertEquals(CaptureDecision.Keep(CaptureGate.OWN_ORDER), CaptureGate.decide(event(title = "Research call", body = levelFreeExit), context))
        nowOwnOrderToo.forEach { body ->
            assertEquals(body, CaptureDecision.Keep(CaptureGate.OWN_ORDER), CaptureGate.decide(event(title = "Research call", body = body), context))
        }

        // This string carries strong evidence ("trade confirmation"), so it stays local though it also reads like a call.
        val flipped = "Wait for trade confirmation above 1450, then BUY RELIANCE SL 1420 TGT 1500"
        assertEquals(CaptureDecision.Keep(CaptureGate.OWN_ORDER), CaptureGate.decide(event(title = "Research call", body = flipped), context))
    }

    @Test
    fun onlyTheCaptureSetLeavesThePhone() {
        fun decide(event: NotificationEventEntity) = CaptureGate.decide(event, context)
        val outside = CaptureDecision.Keep(CaptureGate.OUTSIDE_CAPTURE_SET)

        assertEquals(CaptureDecision.Keep(CaptureGate.NOT_A_CANDIDATE), decide(event(trading = false)))
        assertEquals(outside, decide(event(pkg = "com.zerodha.kite3", source = "Zerodha")))
        assertEquals(outside, decide(event(pkg = "com.whatsapp", source = "WhatsApp", title = "Family Group", group = true)))
        // Finding C1: "Amit" and a non-Indian number are not shaped like a sender id, so they never reach the allow-list.
        assertEquals(
            CaptureDecision.Keep(CaptureGate.SMS_NOT_SENDER_ID),
            decide(event(pkg = "com.google.android.apps.messaging", source = "Messages", title = "Amit"))
        )
        assertEquals(
            CaptureDecision.Keep(CaptureGate.SMS_NOT_SENDER_ID),
            decide(event(pkg = "com.google.android.apps.messaging", source = "Messages", title = "+91 98765 43210"))
        )
        val sms = send(event(pkg = "com.google.android.apps.messaging", source = "Messages", title = "JD-ZERODH-S"))
        assertEquals("SMS" to "ZERODH", sms.medium to sms.channelLabel)
    }

    // Phase 4b: calls no longer classify as TRADING, so the gate, not the classifier, decides what may leave.
    @Test
    fun aBrokerResearchCallStillLeavesButHoldingsAlertsAndOtpsStayLocal() {
        fun decide(category: String, body: String, pkg: String = "com.upstox.pro", title: String = "Upstox") =
            CaptureGate.decide(event(pkg = pkg, title = title, body = body).copy(category = category, isTrading = category == "TRADING"), context)
        val sms = "com.google.android.apps.messaging"
        val ownAccount = CaptureDecision.Keep(CaptureGate.OWN_ACCOUNT)
        val notACandidate = CaptureDecision.Keep(CaptureGate.NOT_A_CANDIDATE)

        // The three broker research-call shapes: 5paisa, Upstox and ICICI Direct.
        assertTrue(decide("MARKET", "BUY RENUKA CMP : 23.62 SL : 22.25 TGT : 26") is CaptureDecision.Send)
        assertTrue(decide("MARKET", "🛠️ Entry : Rs 144.24 🎯 Target : Rs 173.08 🛑 Stoploss : Rs 129.81", title = "📈BUY LCCPROJECT with 20.0% upside potential") is CaptureDecision.Send)
        assertTrue(decide("MARKET", "Buy INDGN around Rs 609 for 12 Month with target price of Rs 750, potential upside of 23.15%.", title = "ICICI Direct") is CaptureDecision.Send)
        assertEquals(ownAccount, decide("MARKET", "Your stock NATSEC has touched 52 week low of 780.0"))
        assertEquals(ownAccount, decide("MARKET", "RELIANCE in your holdings is up 3% today"))
        assertEquals(ownAccount, decide("MARKET", "Your P&L statement for September is ready"))
        assertEquals(notACandidate, decide("PROMOTIONS", "Zero brokerage for a month"))
        assertEquals(notACandidate, decide("OTP", "Your OTP is 482913. Never share your OTP", pkg = sms, title = "JD-ZERODH-S"))
        assertEquals(notACandidate, decide("BANKING", "Rs 5,000 credited to your account", pkg = sms, title = "JD-ZERODH-S"))
        assertTrue(decide("OTHER", "BUY | CROPSTER AGRO | Entry ₹2.82 | Target ₹10 | SL ₹2", pkg = sms, title = "JD-ZERODH-S") is CaptureDecision.Send)
        val groupCall = event(pkg = "com.whatsapp", source = "WhatsApp", title = "StockTips", group = true).copy(category = "MESSAGES", isTrading = false)
        assertTrue(CaptureGate.decide(groupCall, context) is CaptureDecision.Send)
        assertTrue(decide("OTHER", "Your KYC is complete. Download the app", pkg = sms, title = "JD-ZERODH-S") is CaptureDecision.Keep)
        // 4b pre-merge: a watchlist or set alert is the customer's own, even when it reads like a call.
        assertEquals(ownAccount, decide("MARKET", "Watchlist alert: INFY BUY above 1450, SL 1400"))
        assertEquals(ownAccount, decide("OTHER", "Watchlist alert: INFY BUY above 1450, SL 1400", pkg = sms, title = "JD-ZERODH-S"))

        // 4b review C1: account alerts carry no call and never leave, as MARKET or TRADING, at capture or delivery.
        listOf(
            "Price alert: RELIANCE crossed 1450",
            "Alert triggered: INFY is above ₹1,520.00",
            "Today's P&L: +₹4,523 (1.2%)",
            "Portfolio up 2.3% today. Current value ₹5,23,400",
            "You own 10 shares of ITC",
            "You earned a dividend of ₹120 from ITC",
            "Net worth update: ₹12,40,000",
            "Margin shortfall of ₹2,300 in your account",
            "Payout of ₹5,000 processed",
            "IPO allotment status for XYZ",
            "Bid placed for XYZ IPO",
            "SIP of ₹5,000 in ABC fund is due tomorrow",
            "Redemption of ₹10,000 processed",
            "INFY hit 52-week high. You hold 12 shares",
            "Tax P&L report is ready",
            "Stop loss triggered for RELIANCE at 1,400"
        ).forEach { body ->
            listOf("MARKET", "TRADING").forEach { category ->
                assertTrue("$category $body", decide(category, body) is CaptureDecision.Keep)
                assertTrue("$category $body", decide(category, body, title = "Zerodha") is CaptureDecision.Keep)
                assertFalse("$category $body", CaptureGate.queues("com.upstox.pro", category, null, "Upstox", body))
            }
        }
        // I1: an allow-listed group's chatter needs a side and two priced levels, read without "Name: " prefixes.
        listOf("RK: movie entry at 7, meet at PVR", "Priya: Buy milk on the way, entry gate closes at 9", "RK: target reached?", "Good morning all, see you at the meetup").forEach { body ->
            assertEquals(body, notACandidate, CaptureGate.decide(groupCall.copy(body = body), context))
            assertFalse(body, CaptureGate.queues("com.whatsapp", "MESSAGES", true, "StockTips", body))
        }
        // M3: a one-time code beside call-shaped text is never a candidate.
        val tpin = "482913 is your TPIN code to authorise SELL of INFY at LTP 1450 SL 1400. Never share your OTP."
        assertTrue(decide("OTHER", tpin, pkg = sms, title = "JD-ZERODH-S") is CaptureDecision.Keep)
        assertTrue(decide("MARKET", tpin) is CaptureDecision.Keep)
        assertFalse(CaptureGate.queues(sms, "OTHER", null, "JD-ZERODH-S", tpin))

        val call = "BUY RENUKA CMP 23.62 SL 22.25 TGT 26"
        assertTrue(CaptureGate.queues("com.upstox.pro", "MARKET", null, "Upstox", call))
        assertFalse(CaptureGate.queues("com.facebook.orca", "MESSAGES", null, "Rahul", call))
        assertTrue(CaptureGate.queues("org.telegram.messenger", "MESSAGES", true, "StockTips", "Rahul: $call"))
        assertFalse(CaptureGate.queues("org.telegram.messenger", "MESSAGES", false, "Rahul", call))
        assertFalse(CaptureGate.queues("org.telegram.messenger", "MESSAGES", null, "StockTips", call))
        assertFalse(CaptureGate.queues(sms, "OTP", null, "JD-ZERODH-S", "Your OTP is 482913"))
        // I3: the chat allow-list applies at capture when it is known.
        assertFalse(CaptureGate.queues("com.whatsapp", "MESSAGES", true, "Family Group", call, context.chatAllowList))
        assertTrue(CaptureGate.queues("com.whatsapp", "MESSAGES", true, "StockTips", call, context.chatAllowList))
        assertTrue(CaptureGate.queues(sms, "OTHER", null, "JD-ZERODH-S", call, context.chatAllowList))
        assertFalse(CaptureGate.queues(sms, "OTHER", null, "Amit", call, context.chatAllowList))
    }

    @Test
    fun smsLeavesOnlyWhenTheTitleIsShapedLikeASenderId() {
        // Even an allow-listed person name or foreign number must stay local: the title itself must look like a sender id.
        val extended = context.copy(chatAllowList = context.chatAllowList + setOf("+44 7911 123456", "upstox"))
        fun decide(title: String) = CaptureGate.decide(
            event(pkg = "com.google.android.apps.messaging", source = "Messages", title = title),
            extended
        )

        assertEquals(CaptureDecision.Keep(CaptureGate.SMS_NOT_SENDER_ID), decide("Rahul"))
        assertEquals(CaptureDecision.Keep(CaptureGate.SMS_NOT_SENDER_ID), decide("+44 7911 123456"))
        // Sender ids are case-sensitive and uppercase-only; the bare 6-character form was dropped, since a 6-letter name ("Suresh") passed it too.
        assertEquals(CaptureDecision.Keep(CaptureGate.SMS_NOT_SENDER_ID), decide("Suresh"))
        assertEquals(CaptureDecision.Keep(CaptureGate.SMS_NOT_SENDER_ID), decide("SURESH"))
        assertEquals(CaptureDecision.Keep(CaptureGate.SMS_NOT_SENDER_ID), decide("Mr-Suresh"))
        assertEquals(CaptureDecision.Keep(CaptureGate.SMS_NOT_SENDER_ID), decide("ZERODH"))

        assertEquals("ZERODH", (decide("JD-ZERODH-S") as CaptureDecision.Send).message.channelLabel)
        assertEquals("ZERODH", (decide("VM-ZERODH") as CaptureDecision.Send).message.channelLabel)
        assertEquals("UPSTOX", (decide("AD-UPSTOX-S") as CaptureDecision.Send).message.channelLabel)
    }

    // R3: the allow list holds ONLY the lowercased entry, so this can only pass with the uppercase-before-extraction fix.
    @Test
    fun smsAllowListMatchesADltEntryStoredLowercased() {
        val onlyLowercasedEntry = context.copy(chatAllowList = setOf("jd-zerodh-s"))

        val sent = (CaptureGate.decide(
            event(pkg = "com.google.android.apps.messaging", source = "Messages", title = "VM-ZERODH"),
            onlyLowercasedEntry
        ) as CaptureDecision.Send).message

        assertEquals("ZERODH", sent.channelLabel)
    }

    // R3: each name is mentioned MID-line (not a "Name: " prefix, which prefix-stripping alone would remove regardless of the mask).
    @Test
    fun interleavedBatchesEachMaskOnlyTheirOwnSenders() {
        val contextA = context.copy(chatSenders = setOf("Alice"))
        val contextB = context.copy(chatSenders = setOf("Bob"))
        val rowA = event(pkg = "com.whatsapp", source = "WhatsApp", title = "StockTips", group = true, body = "BUY RENUKA CMP 23 SL 22 TGT 26 via Alice")
        val rowB = event(pkg = "com.whatsapp", source = "WhatsApp", title = "StockTips", group = true, body = "BUY RENUKA CMP 23 SL 22 TGT 26 via Bob")
        val mixedRow = event(
            pkg = "com.whatsapp", source = "WhatsApp", title = "StockTips", group = true,
            body = "BUY RENUKA CMP 23 SL 22 TGT 26 via Alice, cross-check Bob"
        )

        val sentA1 = (CaptureGate.decide(rowA, contextA) as CaptureDecision.Send).message
        val sentB1 = (CaptureGate.decide(rowB, contextB) as CaptureDecision.Send).message
        val sentB2 = (CaptureGate.decide(rowB, contextB) as CaptureDecision.Send).message
        val sentA2 = (CaptureGate.decide(rowA, contextA) as CaptureDecision.Send).message
        val sentMixedUnderA = (CaptureGate.decide(mixedRow, contextA) as CaptureDecision.Send).message
        val sentMixedUnderB = (CaptureGate.decide(mixedRow, contextB) as CaptureDecision.Send).message

        assertFalse(sentA1.text.contains("Alice"))
        assertFalse(sentA2.text.contains("Alice"))
        assertFalse(sentB1.text.contains("Bob"))
        assertFalse(sentB2.text.contains("Bob"))
        // The cross-contamination proof: each context's mask acts only on its own sender, never the other's.
        assertFalse(sentMixedUnderA.text.contains("Alice"))
        assertTrue(sentMixedUnderA.text.contains("Bob"))
        assertFalse(sentMixedUnderB.text.contains("Bob"))
        assertTrue(sentMixedUnderB.text.contains("Alice"))
    }

    @Test
    fun appNotificationsWaitUntilTheCaptureListIsCached() {
        val uncached = context.copy(capturePackages = null)

        assertEquals(CaptureDecision.Wait, CaptureGate.decide(event(), uncached))
        val group = event(pkg = "com.whatsapp", source = "WhatsApp", title = "StockTips", group = true)
        assertTrue(CaptureGate.decide(group, uncached) is CaptureDecision.Send)
    }

    @Test
    fun theDeviceEventKeyIsStableAcrossRetriesAndRevealsNoNotificationKey() {
        val row = event(key = "0|com.upstox.pro|7|client-919876543210|10123")
        val first = send(row).deviceEventKey

        assertEquals(first, send(row).deviceEventKey)
        assertFalse(first.contains("9876543210"))
        assertTrue(first.startsWith("n1-") && first.length <= 128)
        val otherInstall = CaptureGate.decide(row, context.copy(deviceSalt = "salt-2")) as CaptureDecision.Send
        assertNotEquals(first, otherInstall.message.deviceEventKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decideRefusesToRunWithABlankDeviceSalt() {
        CaptureGate.decide(event(), context.copy(deviceSalt = " "))
    }

    @Test
    fun theCaptureListKeepsOnlyPackageNames() {
        val data = JSONObject("""{"packages":[{"package":"COM.UPSTOX.PRO","channelId":3},{"package":""}]}""")

        assertEquals(setOf("com.upstox.pro"), parseCaptureList(data))
    }

    @Test
    fun anEmptyPackagesArrayReturnsAnEmptySet() {
        assertEquals(emptySet<String>(), parseCaptureList(JSONObject("""{"packages":[]}""")))
    }

    @Test
    fun malformedCaptureListDataThrows() {
        // Finding I2: malformed data throws so the caller keeps its cached/null list and rows wait, rather
        // than silently resolving to an empty set (which would have sent nothing from apps).
        val malformed = listOf(
            JSONObject("""{"other":"x"}"""),
            JSONObject("""{"packages":"not-an-array"}"""),
            JSONObject("""{"packages":[{"channelId":4}]}"""),
            JSONObject("""{"packages":[{"package":42}]}"""),
            JSONObject("""{"packages":["not-an-object"]}""")
        )
        malformed.forEach { data ->
            try {
                parseCaptureList(data)
                fail("expected IllegalArgumentException for $data")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    // B2 review carry-over: the gate decides by medium first; SMS never takes the chat/group-label path.
    @Test
    fun anSmsRowWithChatGroupTrueGetsNoChatTreatment() {
        val smsWithGroupFlag = event(
            pkg = "com.google.android.apps.messaging", source = "Messages",
            title = "StockTips: Rahul", group = true
        )
        assertEquals(CaptureDecision.Keep(CaptureGate.SMS_NOT_SENDER_ID), CaptureGate.decide(smsWithGroupFlag, context))
    }

    // B4 fix round 1: restore the guard test for inconsistent routing flags.
    @Test
    fun aRowWithInconsistentTradingFlagsIsNotSent() {
        val inconsistent = NotificationEventEntity(
            sourcePackage = "com.upstox.pro", sourceName = "Upstox", sourceKey = "k-1", eventFingerprint = "k-1",
            title = "RENUKA call", body = "BUY RENUKA CMP 23.62 SL 22.25 TGT 26",
            postedAt = 2_000L, category = "MESSAGES", priority = 100, confidence = 0.96f,
            isTrading = true, deliveryState = DeliveryState.PENDING.name, chatGroup = null
        )
        assertEquals(CaptureDecision.Keep(CaptureGate.NOT_A_CANDIDATE), CaptureGate.decide(inconsistent, context))
    }

    // B4 fix round 1: restore the guard test for missing idempotency key.
    @Test
    fun aRowWithABlankSourceKeyIsNotSent() {
        val noKey = event(key = "")
        assertEquals(CaptureDecision.Keep(CaptureGate.NOT_A_CANDIDATE), CaptureGate.decide(noKey, context))
    }

    private fun send(event: NotificationEventEntity) = (CaptureGate.decide(event, context) as CaptureDecision.Send).message

    private fun event(
        pkg: String = "com.upstox.pro",
        source: String = "Upstox",
        title: String = "RENUKA call",
        body: String = "BUY RENUKA CMP 23.62 SL 22.25 TGT 26",
        trading: Boolean = true,
        key: String = "k-1",
        group: Boolean? = null
    ) = NotificationEventEntity(
        sourcePackage = pkg, sourceName = source, sourceKey = key, eventFingerprint = key, title = title, body = body,
        postedAt = 2_000L, category = if (trading) "TRADING" else "MESSAGES", priority = 100, confidence = 0.96f,
        isTrading = trading, deliveryState = if (trading) DeliveryState.PENDING.name else DeliveryState.NOT_APPLICABLE.name,
        chatGroup = group
    )
}
