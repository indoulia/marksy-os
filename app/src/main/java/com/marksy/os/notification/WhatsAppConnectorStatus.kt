package com.marksy.os.notification

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager

/** Local status helper for the optional WhatsApp accessibility connector. */
object WhatsAppConnectorStatus {
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val packageName = context.packageName
        val serviceName = MarksyWhatsAppAccessibilityService::class.java.name
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                serviceInfo.packageName.equals(packageName, ignoreCase = true) &&
                    serviceInfo.name.equals(serviceName, ignoreCase = true)
            }
    }
}
