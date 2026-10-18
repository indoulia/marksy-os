package com.marksy.os.upstox

/** What Settings shows as saved; the secret and tokens are never loaded into the form. */
data class UpstoxSaved(
    val apiKey: String = "",
    val hasSecret: Boolean = false,
    val redirect: String = UpstoxOAuth.DEFAULT_REDIRECT,
    val hasAnalytics: Boolean = false,
    val analyticsExpiresAt: Long? = null,
    val sandboxExpiresAt: Long? = null
) {
    override fun toString() = "UpstoxSaved(apiKey=***, hasSecret=$hasSecret, redirect=$redirect, hasAnalytics=$hasAnalytics)"

    companion object {
        fun load(oauth: UpstoxOAuthStore, analytics: UpstoxTokenStore): UpstoxSaved {
            val credentials = oauth.credentials()
            val token = analytics.getToken()
            return UpstoxSaved(
                apiKey = credentials?.apiKey.orEmpty(),
                hasSecret = credentials != null,
                redirect = credentials?.redirectUri ?: UpstoxOAuth.DEFAULT_REDIRECT,
                hasAnalytics = token != null,
                analyticsExpiresAt = token?.let(UpstoxJwt::expiresAt),
                sandboxExpiresAt = oauth.sandboxExpiresAt()
            )
        }
    }
}

/** The Settings form: typed or QR-filled text over [saved]; a blank secret or token means "keep the saved one". */
data class UpstoxDraft(
    val saved: UpstoxSaved,
    val values: Map<UpstoxField, String> = mapOf(UpstoxField.API_KEY to saved.apiKey, UpstoxField.REDIRECT to saved.redirect),
    val fromQr: Set<UpstoxField> = emptySet()
) {
    val dirty: Boolean get() = changes().isNotEmpty()

    fun text(field: UpstoxField): String = values[field].orEmpty()

    fun edit(field: UpstoxField, text: String) = copy(values = values + (field to text), fromQr = fromQr - field)

    fun apply(import: UpstoxQrImport.Ok) = copy(values = values + import.fields, fromQr = fromQr + import.fields.keys)

    fun discard() = UpstoxDraft(saved)

    /** Trimmed fields Save would write. */
    fun changes(): Map<UpstoxField, String> = UpstoxField.entries.mapNotNull { field ->
        val value = text(field).trim()
        val changed = when (field) {
            UpstoxField.API_KEY -> value != saved.apiKey
            UpstoxField.REDIRECT -> value != saved.redirect
            else -> value.isNotEmpty()
        }
        if (changed) field to value else null
    }.toMap()

    /** Why Save can't run yet, or null. */
    fun problem(): String? {
        val changes = changes()
        changes.forEach { (field, value) -> UpstoxQrImport.fieldProblem(field, value)?.let { return it } }
        if (changes.keys.any { it in CREDENTIALS }) {
            if (text(UpstoxField.API_KEY).isBlank()) return "Add the API key too."
            if (UpstoxField.API_SECRET !in changes && !saved.hasSecret) return "Add the API secret too."
        }
        return UpstoxQrImport.sameTokenProblem(changes[UpstoxField.ANALYTICS_TOKEN], changes[UpstoxField.SANDBOX_TOKEN])
    }

    override fun toString() = "UpstoxDraft(changed=${changes().keys.joinToString { it.key }}, fromQr=${fromQr.joinToString { it.key }})"

    companion object {
        val CREDENTIALS = setOf(UpstoxField.API_KEY, UpstoxField.API_SECRET, UpstoxField.REDIRECT)
    }
}

/** How the Analytics Token check against Upstox went. */
enum class TokenCheck { OK, REJECTED, UNREACHABLE }

/** Writes a valid draft's changed fields; an Analytics Token Upstox rejects is the only thing left unsaved. */
class UpstoxSettingsSaver(
    private val oauth: UpstoxOAuthStore,
    private val analytics: UpstoxTokenStore,
    private val check: suspend (String) -> TokenCheck
) {
    data class Result(val saved: Set<UpstoxField>, val analytics: TokenCheck?, val signInDropped: Boolean)

    suspend fun save(draft: UpstoxDraft, now: Long): Result {
        require(draft.problem() == null) { "draft has a problem" }
        val changes = draft.changes()
        val saved = changes.keys.toMutableSet()
        val outcome = changes[UpstoxField.ANALYTICS_TOKEN]?.let { token ->
            check(token).also { if (it == TokenCheck.REJECTED) saved -= UpstoxField.ANALYTICS_TOKEN else analytics.save(token) }
        }
        val signedIn = oauth.accessToken(now) != null
        if (changes.keys.any { it in UpstoxDraft.CREDENTIALS }) {
            val existing = oauth.credentials()
            oauth.saveCredentials(
                changes[UpstoxField.API_KEY] ?: existing?.apiKey.orEmpty(),
                changes[UpstoxField.API_SECRET] ?: existing?.apiSecret.orEmpty(),
                changes[UpstoxField.REDIRECT] ?: existing?.redirectUri ?: UpstoxOAuth.DEFAULT_REDIRECT
            )
        }
        changes[UpstoxField.SANDBOX_TOKEN]?.let { oauth.saveSandboxToken(it, now) }
        return Result(saved, outcome, signedIn && oauth.accessToken(now) == null)
    }
}
