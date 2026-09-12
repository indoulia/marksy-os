package com.marksy.os.notification

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Conservative extraction helpers for WhatsApp's visible accessibility tree.
 *
 * WhatsApp does not expose a stable public message API to third-party personal apps,
 * so this connector intentionally works only with text currently exposed by Android's
 * accessibility framework. It does not inspect WhatsApp's private storage/database.
 */
object WhatsAppAccessibilityParser {
    data class VisibleConversation(
        val candidateSenderTexts: List<String>,
        val messageTexts: List<String>
    )

    fun parse(root: AccessibilityNodeInfo?): VisibleConversation {
        if (root == null) return VisibleConversation(emptyList(), emptyList())

        val texts = buildList {
            collectText(root, this)
        }.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(250)
            .toList()

        // We deliberately return the visible text without pretending we know which
        // WhatsApp node is the sender. The service applies the configured sender
        // allow-list before persisting anything.
        return VisibleConversation(
            candidateSenderTexts = texts,
            messageTexts = texts
        )
    }

    private fun collectText(node: AccessibilityNodeInfo, output: MutableList<String>) {
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(output::add)
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(output::add)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                try {
                    collectText(child, output)
                } finally {
                    child.recycle()
                }
            }
        }
    }
}
