package com.marksy.os.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marksy.os.capture.CaptureMessages
import com.marksy.os.capture.CaptureMethod
import com.marksy.os.capture.CaptureState
import com.marksy.os.capture.WorkflowState
import com.marksy.os.capture.fields
import com.marksy.os.capture.CaptureFailure
import com.marksy.os.capture.projection.rememberScreenCaptureLauncher
import com.marksy.os.capture.rememberScreenshotPicker
import com.marksy.os.data.MarksyContainer
import com.marksy.os.data.local.CaptureWorkflowEntity
import com.marksy.os.data.local.TipCandidateEntity
import com.marksy.os.notification.OriginalAppLauncher
import kotlinx.coroutines.launch

private const val SHOWN_TO_REVIEW = 3

/** "View tip" and "Capture tip" for a teaser workflow, each started only by the user's own tap. */
class CaptureActions(val view: (Long) -> Unit = {}, val capture: (Long) -> Unit = {})

/** Created once at the activity level: the consent launcher must outlive any dialog that starts it. */
@Composable
fun rememberCaptureActions(notice: (String) -> Unit): CaptureActions {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val launcher = rememberScreenCaptureLauncher()
    return remember(launcher, notice) {
        CaptureActions(
            view = { id ->
                scope.launch {
                    if (MarksyContainer.workflowSourceOpener(context).view(id) == OriginalAppLauncher.Opened.UNAVAILABLE) notice(CaptureMessages.failure(CaptureFailure.SOURCE_UNAVAILABLE))
                }
            },
            capture = { id -> scope.launch { CaptureMessages.start(launcher.start(id))?.let(notice) } }
        )
    }
}

/** What the Captured tab shows beyond delivered calls: candidates to review and teaser notifications to open or capture. */
class CaptureInbox(
    val candidates: List<TipCandidateEntity> = emptyList(),
    val workflows: List<CaptureWorkflowEntity> = emptyList(),
    val sourceName: (String) -> String = { it },
    val canCapture: (String) -> Boolean = { false },
    val actions: CaptureActions = CaptureActions(),
    val onReview: (Long) -> Unit = {},
    val onAddScreenshot: () -> Unit = {}
) {
    val count: Int get() = candidates.size + workflows.size
    val isEmpty: Boolean get() = count == 0
}

/** Live inbox: the candidate and workflow flows, the Photo Picker, and the review dialog a row opens. */
@Composable
fun rememberCaptureInbox(actions: CaptureActions): CaptureInbox {
    val context = LocalContext.current.applicationContext
    val dao = remember { MarksyContainer.database(context).captureDao() }
    val registry = remember { MarksyContainer.captureSources(context) }
    val candidates by dao.observeToReview().collectAsStateWithLifecycle(emptyList())
    val workflows by dao.observeOpenWorkflows().collectAsStateWithLifecycle(emptyList())
    var reviewing by rememberSaveable { mutableStateOf<Long?>(null) }
    val pick = rememberScreenshotPicker()
    reviewing?.let { CaptureReviewHost(it) { reviewing = null } }
    return CaptureInbox(
        candidates, workflows.filter { it.state in TEASER_STATES }, { registry.resolve(it)?.displayName ?: "an app" },
        { registry.resolve(it)?.offersScreenCapture == true }, actions, { reviewing = it }, pick
    )
}

// Mid-capture states and the review state are covered by the session bar and the candidate row.
private val TEASER_STATES = setOf(
    WorkflowState.NEEDS_SOURCE_VIEW, WorkflowState.USER_OPENED_SOURCE, WorkflowState.CAPTURE_REQUESTED, WorkflowState.CAPTURE_DENIED,
    WorkflowState.CAPTURE_FAILED, WorkflowState.PROTECTED_SCREEN, WorkflowState.SOURCE_UNAVAILABLE
).map { it.name }.toSet()

/** The "To review" lane at the top of the Captured list. */
fun LazyListScope.captureToReview(inbox: CaptureInbox, now: Long, expanded: Boolean, onToggle: () -> Unit) {
    if (inbox.isEmpty) return
    item(key = "to-review") { SectionLabel("To review", inbox.count, MarksyTheme.Warning) }
    items(inbox.workflows, key = { "w${it.id}" }) { TeaserCard(it, inbox, now) }
    if (inbox.candidates.isNotEmpty()) item(key = "to-review-candidates") { CandidateGroup(inbox, now, expanded, onToggle) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeaserCard(w: CaptureWorkflowEntity, inbox: CaptureInbox, now: Long) {
    val failure = w.failureCode
    MarksyCard(border = MarksyTheme.YellowImportant.copy(alpha = 0.4f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MarksyBadge(if (w.reason == "REDACTED") "Hidden" else "Teaser", MarksyTheme.YellowImportant, MarksyTheme.BadgeImportantBg)
            Spacer(Modifier.width(MarksySpace.Gap))
            Text("Tip from ${inbox.sourceName(w.sourcePackage)} · ${compactTime(w.createdAt, now) ?: ""}", color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (failure != null) CaptureMessages.failure(failure) else "The notification doesn't show the tip. Open the app to read it, or capture it from the screen.",
            color = MarksyTheme.TextSecondary, style = MarksyType.Small
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Inner)) {
            Pill("View tip") { inbox.actions.view(w.id) }
            if (inbox.canCapture(w.sourcePackage)) Pill("Capture tip", selected = true) { inbox.actions.capture(w.id) }
        }
    }
}

@Composable
private fun CandidateGroup(inbox: CaptureInbox, now: Long, expanded: Boolean, onToggle: () -> Unit) {
    val shown = if (expanded) inbox.candidates else inbox.candidates.take(SHOWN_TO_REVIEW)
    val hidden = inbox.candidates.size - SHOWN_TO_REVIEW
    MarksyGroupCard {
        shown.forEachIndexed { i, c ->
            if (i > 0) MarksyDivider()
            Column(Modifier.marksyRow(onClick = { inbox.onReview(c.id) })) { CandidateRow(c, inbox, now) }
        }
        if (hidden > 0) {
            MarksyDivider()
            Row(Modifier.marksyRow(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
                Text(if (expanded) "Show less" else "$hidden more", color = MarksyTheme.TextSecondary, style = MarksyType.Small, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CandidateRow(c: TipCandidateEntity, inbox: CaptureInbox, now: Long) {
    val f = c.fields
    val side = f.side?.name?.lowercase()?.replaceFirstChar { it.uppercase() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(listOfNotNull(side, f.symbol).joinToString(" ").ifEmpty { "Tip to check" }, color = MarksyTheme.TextPrimary, style = MarksyType.Subhead, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(MarksySpace.Inner))
        Text(compactTime(c.capturedAt, now) ?: "", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
    }
    val levels = levelsLine(TipLevels(side, f.symbol, f.entry, f.target, f.stopLoss, null))
    if (levels.isNotEmpty()) Text(levels, color = MarksyTheme.TextSecondary, style = MarksyType.Small)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MarksySpace.Inner), verticalArrangement = Arrangement.spacedBy(MarksySpace.Tight), itemVerticalAlignment = Alignment.CenterVertically) {
        MarksyBadge(if (c.state == CaptureState.REVIEW_REQUIRED.name) "Check details" else "Ready", if (c.state == CaptureState.REVIEW_REQUIRED.name) MarksyTheme.YellowImportant else MarksyTheme.PrimaryEmerald, if (c.state == CaptureState.REVIEW_REQUIRED.name) MarksyTheme.BadgeImportantBg else MarksyTheme.BadgeTradingBg)
        Text("${CaptureMessages.method(CaptureMethod.valueOf(c.method))} · ${c.sourcePackage?.let(inbox.sourceName) ?: "unknown app"}", color = MarksyTheme.TextMuted, style = MarksyType.Meta)
    }
}
