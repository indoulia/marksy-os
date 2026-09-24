package com.marksy.os.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthModelsTest {
    @Test
    fun parsesSessionResponse() {
        val envelope = JSONObject(
            """
            {
              "data": {
                "sessionToken": "sess_abc123",
                "userId": "prsingh",
                "issuedAt": "2026-09-25T09:00:00Z",
                "expiresAt": "2026-09-25T17:00:00Z",
                "readOnly": false
              },
              "meta": {}
            }
            """
        )

        val session = SessionResponseDto.parse(envelope)

        assertEquals("sess_abc123", session.sessionToken)
        assertEquals("prsingh", session.userId)
        assertEquals("2026-09-25T17:00:00Z", session.expiresAt)
        assertFalse(session.readOnly)
    }

    @Test
    fun readOnlyDefaultsToFalseWhenAbsent() {
        val envelope = JSONObject(
            """{"data": {"sessionToken": "sess_abc123", "userId": "prsingh", "issuedAt": "2026-09-25T09:00:00Z", "expiresAt": "2026-09-25T17:00:00Z"}, "meta": {}}"""
        )

        val session = SessionResponseDto.parse(envelope)

        assertFalse(session.readOnly)
    }

    @Test
    fun readOnlyTrueWhenPresent() {
        val envelope = JSONObject(
            """{"data": {"sessionToken": "s", "userId": "u", "issuedAt": "2026-09-25T09:00:00Z", "expiresAt": "2026-09-25T17:00:00Z", "readOnly": true}, "meta": {}}"""
        )

        assertTrue(SessionResponseDto.parse(envelope).readOnly)
    }
}
