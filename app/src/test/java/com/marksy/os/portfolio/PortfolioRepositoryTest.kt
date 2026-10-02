package com.marksy.os.portfolio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.marksy.os.gateway.TestAndroidKeyStoreProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.Security

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortfolioRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    companion object {
        @BeforeClass
        @JvmStatic
        fun setupAndroidKeyStore() {
            if (Security.getProvider("AndroidKeyStore") == null) Security.addProvider(TestAndroidKeyStoreProvider())
        }
    }

    @Test
    fun returningWithinAMinuteReusesTheLastFetchButAPullAlwaysFetches(): Unit = runBlocking {
        var calls = 0
        val source = HoldingsSource { now -> calls++; HoldingsResult.Ok(HoldingsSnapshot("upstox", emptyList(), now)) }
        val repo = PortfolioRepository(context, source).apply { disconnect() }
        repo.refresh(1_000_000L, force = false)
        repo.refresh(1_030_000L, force = false)
        assertEquals(1, calls)
        repo.refresh(1_040_000L, force = true)
        assertEquals(2, calls)
        repo.refresh(1_100_001L, force = false)
        assertEquals(3, calls)
    }
}
