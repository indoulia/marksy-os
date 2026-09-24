package com.marksy.os.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.marksy.os.market.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FixtureIpoClient(
    private val list: List<IpoListItemDto>,
    private val counts: IpoStageCountsDto
) : MarketApiClient {
    override suspend fun marketSummary() = throw NotImplementedError()
    override suspend fun liveQuotes(symbols: List<String>?) = throw NotImplementedError()
    override suspend fun liveFeedHealth() = throw NotImplementedError()
    override suspend fun indexHistory(name: String, range: String) = throw NotImplementedError()
    override suspend fun sectors() = emptyList<SectorOptionDto>()
    override suspend fun instrument(symbol: String) = throw NotImplementedError()
    override suspend fun activePredictions(cursor: String?) = throw NotImplementedError()
    override suspend fun activePrediction(id: Int) = throw NotImplementedError()
    override suspend fun ipos(stage: String?, query: String?) = list
    override suspend fun ipoAttention(limit: Int) = emptyList<IpoAttentionItemDto>()
    override suspend fun ipoStageCounts() = counts
    override suspend fun ipoDetail(id: String) = throw NotImplementedError()
    override suspend fun ipoHistory(id: String) = emptyList<IpoHistoryEntryDto>()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IpoScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun ipo(id: String, company: String, stage: String) = IpoListItemDto(
        id = id, companyName = company, issueName = null, isSme = false, sector = null, stage = stage,
        opensOn = null, closesOn = null, listsOn = null, terms = null
    )

    @Test
    fun listedIposShowCompanyNames() {
        val repository = MarketIntelligenceRepository(
            FixtureIpoClient(
                list = listOf(ipo("ipo-1", "Acme Robotics", "OPEN")),
                counts = IpoStageCountsDto(byStage = mapOf("OPEN" to 1), total = 1)
            )
        )

        compose.setContent { IpoScreen(repository = repository, padding = PaddingValues()) }
        compose.waitForIdle()

        compose.onNodeWithText("Acme Robotics").assertExists()
    }

    @Test
    fun emptyIpoListShowsEmptyState() {
        val repository = MarketIntelligenceRepository(FixtureIpoClient(list = emptyList(), counts = IpoStageCountsDto(byStage = emptyMap(), total = 0)))

        compose.setContent { IpoScreen(repository = repository, padding = PaddingValues()) }
        compose.waitForIdle()

        compose.onNodeWithText("No IPOs", substring = true).assertExists()
    }

    @Test
    fun stageFilterChipsComeFromServerCounts() {
        val repository = MarketIntelligenceRepository(
            FixtureIpoClient(
                list = listOf(ipo("ipo-1", "Acme Robotics", "OPEN")),
                counts = IpoStageCountsDto(byStage = mapOf("OPEN" to 1, "UPCOMING" to 2), total = 3)
            )
        )

        compose.setContent { IpoScreen(repository = repository, padding = PaddingValues()) }
        compose.waitForIdle()

        compose.onNodeWithText("UPCOMING", substring = true).assertExists()
    }
}
