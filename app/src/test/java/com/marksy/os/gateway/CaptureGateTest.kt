package com.marksy.os.gateway

import com.marksy.os.data.local.DeliveryState
import com.marksy.os.data.local.NotificationEventEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

        val telegram = send(event(pkg = "org.telegram.messenger", source = "Telegram", title = "Rahul @ StockTips", group = true))
        assertEquals("TELEGRAM" to "StockTips", telegram.medium to telegram.channelLabel)
        assertFalse(telegram.text.contains("Rahul"))
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

    @Test
    fun theCustomersOwnOrderNotificationsNeverLeaveThePhone() {
        val ownOrder = CaptureDecision.Keep(CaptureGate.OWN_ORDER)
        listOf(
            "Order update" to "Your order to BUY 10 RELIANCE is executed",
            "Order filled" to "BUY 10 TCS filled at 3900",
            "Order placed" to "Your SELL order for 5 INFY has been placed",
            "Order cancelled" to "Your BUY order for 5 INFY was cancelled",
            "Order rejected" to "RMS: insufficient margin for RELIANCE",
            "GTT triggered" to "Your GTT for RELIANCE has been triggered"
        ).forEach { (title, body) -> assertEquals(title, ownOrder, CaptureGate.decide(event(title = title, body = body), context)) }

        val brokerSms = event(pkg = "com.google.android.apps.messaging", source = "Messages", title = "JD-ZERODH-S",
            body = "Your order to BUY 10 RELIANCE is executed at 1450")
        assertEquals(ownOrder, CaptureGate.decide(brokerSms, context))
        val researchCall = event(title = "Research call", body = "Buy order: RENUKA above 24, target 26, stop loss 22")
        assertTrue(CaptureGate.decide(researchCall, context) is CaptureDecision.Send)
    }

    @Test
    fun onlyTheCaptureSetLeavesThePhone() {
        fun decide(event: NotificationEventEntity) = CaptureGate.decide(event, context)
        val outside = CaptureDecision.Keep(CaptureGate.OUTSIDE_CAPTURE_SET)

        assertEquals(CaptureDecision.Keep(CaptureGate.NOT_TRADING), decide(event(trading = false)))
        assertEquals(outside, decide(event(pkg = "com.zerodha.kite3", source = "Zerodha")))
        assertEquals(outside, decide(event(pkg = "com.whatsapp", source = "WhatsApp", title = "Family Group", group = true)))
        assertEquals(outside, decide(event(pkg = "com.google.android.apps.messaging", source = "Messages", title = "Amit")))
        assertEquals(
            CaptureDecision.Keep(CaptureGate.MASKED_LABEL),
            decide(event(pkg = "com.google.android.apps.messaging", source = "Messages", title = "+91 98765 43210"))
        )
        val sms = send(event(pkg = "com.google.android.apps.messaging", source = "Messages", title = "JD-ZERODH-S"))
        assertEquals("SMS" to "ZERODH", sms.medium to sms.channelLabel)
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

    @Test
    fun theCaptureListKeepsOnlyPackageNames() {
        val data = JSONObject("""{"packages":[{"package":"COM.UPSTOX.PRO","channelId":3},{"package":""},{"channelId":4}]}""")

        assertEquals(setOf("com.upstox.pro"), parseCaptureList(data))
    }

    // B2 review carry-over: the gate decides by medium first; SMS never takes the chat/group-label path.
    @Test
    fun anSmsRowWithChatGroupTrueGetsNoChatTreatment() {
        val smsWithGroupFlag = event(
            pkg = "com.google.android.apps.messaging", source = "Messages",
            title = "StockTips: Rahul", group = true
        )
        assertEquals(CaptureDecision.Keep(CaptureGate.OUTSIDE_CAPTURE_SET), CaptureGate.decide(smsWithGroupFlag, context))
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
