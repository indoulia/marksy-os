package com.marksy.os.capture

import com.marksy.os.notification.CaptureMedium
import com.marksy.os.notification.NotificationClassifier
import java.util.Locale

data class CaptureSource(
    val packageName: String,
    val displayName: String,
    val medium: CaptureMedium,
    val offersScreenCapture: Boolean,
    val deliverable: Boolean
)

/** The only place source-specific capture behavior lives; sources are allow-listed by package identity. */
class CaptureSourceRegistry(
    /** `CaptureStore.capturePackages()`: null until the server capture list was fetched once. */
    private val capturePackages: () -> Set<String>?,
    private val displayName: (String) -> String,
    private val marketPackages: Set<String> = NotificationClassifier.marketSourcePackages
) {
    fun captureListKnown(): Boolean = capturePackages() != null

    /** Null when the package is blank or not allow-listed. */
    fun resolve(packageName: String?): CaptureSource? {
        val pkg = packageName?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val medium = CaptureMedium.of(pkg)
        // Chats and SMS can never prove a group identity, so they stay local.
        if (medium != CaptureMedium.APP_NOTIFICATION) return CaptureSource(pkg, displayName(pkg), medium, offersScreenCapture = false, deliverable = false)
        val listed = capturePackages()?.contains(pkg) == true
        if (!listed && pkg !in marketPackages) return null
        return CaptureSource(pkg, displayName(pkg), medium, offersScreenCapture = true, deliverable = listed)
    }

    /** App sources a user may pick for an unverified capture. */
    fun allowListed(): List<CaptureSource> =
        (capturePackages().orEmpty() + marketPackages).mapNotNull(::resolve).filter { it.medium == CaptureMedium.APP_NOTIFICATION }
            .distinctBy { it.packageName }.sortedBy { it.displayName.lowercase(Locale.ROOT) }
}
