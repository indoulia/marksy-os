package com.marksy.os.upstox

import android.util.JsonReader
import android.util.JsonToken
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.StringReader

/** A setting the Upstox QR code or the Settings form can fill. */
enum class UpstoxField(val key: String, val label: String, val maxChars: Int) {
    API_KEY("api_key", "API key", 128),
    API_SECRET("api_secret", "API secret", 128),
    REDIRECT("redirect_uri", "Redirect URL", 512),
    ANALYTICS_TOKEN("analytics_token", "Analytics Token", 2048),
    SANDBOX_TOKEN("sandbox_token", "Sandbox token", 2048);

    companion object {
        fun of(key: String): UpstoxField? = entries.firstOrNull { it.key == key }
    }
}

/** A scanned Marksy Upstox QR code (format v1, closed schema). It only fills the form; it never saves. */
sealed class UpstoxQrImport {
    data class Ok(val fields: Map<UpstoxField, String>) : UpstoxQrImport() {
        override fun toString() = "Ok(${fields.keys.joinToString { it.key }})"
    }

    data class Rejected(val message: String) : UpstoxQrImport()

    companion object {
        const val TYPE = "marksy-upstox"
        const val VERSION = 1
        const val MAX_BYTES = 4096
        private const val NOT_OURS = "This isn't a Marksy Upstox code."
        private val PRINTABLE = Regex("[\\x21-\\x7E]+")
        private val LOOPBACK = setOf("127.0.0.1", "localhost", "::1")

        fun parse(scanned: String): UpstoxQrImport {
            if (scanned.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return Rejected("The code is over 4 KB.")
            val text = scanned.removePrefix("\uFEFF").trim()
            if (!text.startsWith("{")) return Rejected(NOT_OURS)
            val entries = try {
                read(text)
            } catch (e: Repeated) {
                return Rejected("${quote(e.key)} appears twice.")
            } catch (e: Exception) {
                return Rejected("This QR isn't valid JSON.")
            }
            if (entries["type"] != TYPE) return Rejected(NOT_OURS)
            versionProblem(entries["v"])?.let { return Rejected(it) }
            val fields = LinkedHashMap<UpstoxField, String>()
            for ((key, value) in entries) {
                if (key == "type" || key == "v") continue
                if (key == "access_token") return Rejected("It contains an access token. Marksy only takes that from the Upstox sign-in.")
                val field = UpstoxField.of(key) ?: return Rejected("It has a field Marksy doesn't accept: ${quote(key)}.")
                if (value !is String) return Rejected("${quote(key)} must be text.")
                val trimmed = value.trim()
                if (trimmed.isEmpty()) return Rejected("${quote(key)} is empty. Leave a field out to keep the saved value.")
                fieldProblem(field, trimmed, quote(key))?.let { return Rejected(it) }
                fields[field] = trimmed
            }
            if (fields.isEmpty()) return Rejected("This code has nothing to import.")
            sameTokenProblem(fields[UpstoxField.ANALYTICS_TOKEN], fields[UpstoxField.SANDBOX_TOKEN])?.let { return Rejected(it) }
            return Ok(fields)
        }

        /** Why [value] can't fill [field], or null; [name] is how the message names the field. */
        fun fieldProblem(field: UpstoxField, value: String, name: String = field.label): String? = when {
            value.isEmpty() -> "$name is empty."
            !PRINTABLE.matches(value) -> "$name has spaces or characters Marksy can't use."
            value.length > field.maxChars -> "$name is too long."
            field == UpstoxField.REDIRECT -> redirectProblem(value)
            else -> null
        }

        fun sameTokenProblem(analytics: String?, sandbox: String?): String? =
            if (analytics != null && analytics == sandbox) "The same token is in both token fields." else null

        private fun redirectProblem(value: String): String? {
            if ('\\' in value) return "The redirect URL can't contain a backslash."
            val url = value.toHttpUrlOrNull()
            if (url == null || !(url.isHttps || url.host in LOOPBACK)) return "The redirect URL must be https, or http to 127.0.0.1 on this phone."
            if ('@' in value || url.fragment != null) return "The redirect URL can't have a user name or a #fragment."
            return null
        }

        private fun versionProblem(v: Any?): String? {
            val n = (v as? Num)?.text?.takeIf { t -> t.isNotEmpty() && t.all(Char::isDigit) }?.toIntOrNull()
                ?: return "This code has no valid version."
            return when {
                n > VERSION -> "This code was made for a newer Marksy (version $n). Update the app."
                n < VERSION -> "This code's version isn't supported."
                else -> null
            }
        }

        private fun quote(key: String) = "\"${key.take(40)}\""

        // Streaming read: the phone's org.json silently keeps the last of two repeated keys.
        private fun read(text: String): Map<String, Any> {
            val reader = JsonReader(StringReader(text))
            val out = LinkedHashMap<String, Any>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name in out) throw Repeated(name)
                out[name] = when (reader.peek()) {
                    JsonToken.STRING -> reader.nextString()
                    JsonToken.NUMBER -> Num(reader.nextString())
                    else -> Other.also { reader.skipValue() }
                }
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("trailing content")
            return out
        }

        private class Repeated(val key: String) : Exception()
        private class Num(val text: String)
        private object Other
    }
}
