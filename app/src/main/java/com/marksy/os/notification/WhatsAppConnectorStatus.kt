package com.marksy.os.notification

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager

/** Local status helper for the optional WhatsApp accessibility connector. */
object WhatsAppConnectorStatus {
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                info.resolveInfo?.serviceInfo?.packageName == context.packageName &&
                    info.resolveInfo?.serviceInfo?.name == MarksyWhatsAppAccessibilityService::class.java.name
            }
    }
}
