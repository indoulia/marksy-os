package com.marksy.os.market

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.marksy.os.gateway.AuthApiClient
import com.marksy.os.gateway.AuthRepository
import com.marksy.os.gateway.AuthSessionStore
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric is required here only because AuthSessionStore's constructor calls
// ApplicationProvider.getApplicationContext() (via the fake repository below),
// which needs a Robolectric-managed application context to resolve at all --
// NOT because this test touches Android Keystore. Construction-time validation
// only calls normalizeBaseUrl(); the fake repository's methods are never invoked,
// so unlike AuthSessionStoreTest this file needs no TestAndroidKeyStoreProvider
// setup.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealMarketApiClientTest {
    @Test
    fun rejectsNonHttpsBaseUrl() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeAuthRepository = AuthRepository(
            client = object : AuthApiClient {
                override suspend fun login(userId: String, password: String) = throw NotImplementedError()
                override suspend fun refresh(currentToken: String) = throw NotImplementedError()
                override suspend fun logout(currentToken: String) = throw NotImplementedError()
            },
            store = AuthSessionStore(context)
        )

        assertThrows(IllegalArgumentException::class.java) {
            RealMarketApiClient(authRepository = fakeAuthRepository, baseUrl = "http://insecure.example.com/api/v1")
        }
    }
}
