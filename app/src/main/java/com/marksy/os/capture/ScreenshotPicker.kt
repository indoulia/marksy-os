package com.marksy.os.capture

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.marksy.os.data.MarksyContainer
import kotlinx.coroutines.launch

/**
 * "Add screenshot": the system Photo Picker grants one image without any storage permission.
 * Returns the launch action; a cancelled pick adds nothing.
 */
@Composable
fun rememberScreenshotPicker(onOutcome: (CaptureOutcome) -> Unit): () -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onOutcome)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            latest(MarksyContainer.imageIntake(context).intake(context.contentResolver, uri, CaptureMethod.USER_SELECTED_IMAGE))
        }
    }
    return remember(picker) { { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
}
