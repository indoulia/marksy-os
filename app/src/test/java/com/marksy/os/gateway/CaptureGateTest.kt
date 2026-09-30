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
    }

    @Test
    fun everyKnownSenderIsMaskedRegardlessOfPresenceInTheRow() {
        // Finding C3: mask every known chatSenders name, not only the ones this row itself names.
        val withBothSenders = context.copy(chatSenders = setOf("Amit", "Rahul"))
        val whatsapp = (CaptureGate.decide(
            event(
                pkg = "com.whatsapp", source = "WhatsApp", title = "StockTips: Amit", group = true,
                body = "Amit: Rahul's RENUKA call hit target"
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
                body = "Amit: thanks RK"
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
    }

    @Test
    fun researchCallsAndSourceExitsStillGo() {
        val stillGo = listOf(
            "Target 26 achieved. Trade completed, book profits",
            "BUY RENUKA CMP 23.62 SL 22.25 TGT 26"
        )
        // These two now carry strong own-order evidence ("trade ... triggered", "order ... placed") on their own; see task-B3-report.md round 2.
        val nowOwnOrderToo = listOf(
            "Our RELIANCE trade: Stop loss triggered, exit now",
            "Buy order to be placed above 24, target 26, SL 22"
        )
        (stillGo + nowOwnOrderToo).forEach { body ->
            // A group row is never subject to the own-order check, so it still sends regardless.
            val group = event(pkg = "com.whatsapp", source = "WhatsApp", title = "StockTips", body = body, group = true)
            assertTrue(body, CaptureGate.decide(group, context) is CaptureDecision.Send)
        }
        stillGo.forEach { body ->
            assertTrue(body, CaptureGate.decide(event(title = "Research call", body = body), context) is CaptureDecision.Send)
        }
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

        assertEquals(CaptureDecision.Keep(CaptureGate.NOT_TRADING), decide(event(trading = false)))
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
        val rowA = event(pkg = "com.whatsapp", source = "WhatsApp", title = "StockTips", group = true, body = "RENUKA call via Alice hit target")
        val rowB = event(pkg = "com.whatsapp", source = "WhatsApp", title = "StockTips", group = true, body = "RENUKA call via Bob hit target")
        val mixedRow = event(
            pkg = "com.whatsapp", source = "WhatsApp", title = "StockTips", group = true,
            body = "RENUKA call via Alice, cross-check Bob hit target"
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
        assertEquals(CaptureDecision.Keep(CaptureGate.NOT_TRADING), CaptureGate.decide(inconsistent, context))
    }

    // B4 fix round 1: restore the guard test for missing idempotency key.
    @Test
    fun aRowWithABlankSourceKeyIsNotSent() {
        val noKey = event(key = "")
        assertEquals(CaptureDecision.Keep(CaptureGate.NOT_TRADING), CaptureGate.decide(noKey, context))
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
