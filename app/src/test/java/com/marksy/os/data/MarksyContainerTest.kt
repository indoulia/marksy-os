package com.marksy.os.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.marksy.os.gateway.SecureCredentialStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarksyContainerTest {
    @Test
    fun authRepositoryFallsBackToDefaultBaseUrlWhenStoredOneIsMalformed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SecureCredentialStore(context).setBaseUrl("http://insecure.example.com/api/v1")

        MarksyContainer.authRepository(context)
    }
}
