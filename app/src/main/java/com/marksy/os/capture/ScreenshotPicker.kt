package com.marksy.os.capture

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * "Add screenshot": the system Photo Picker grants one image without any storage permission.
 * Returns the launch action; a cancelled pick adds nothing. The outcome reaches MainActivity via [CaptureRouting.pending].
 */
@Composable
fun rememberScreenshotPicker(): () -> Unit {
    val context = LocalContext.current.applicationContext
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) CaptureRouting.intake(context, uri, CaptureMethod.USER_SELECTED_IMAGE)
    }
    return remember(picker) { { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
}
