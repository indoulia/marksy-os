package com.marksy.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marksy.os.gateway.AuthRepository
import kotlinx.coroutines.launch

private const val SAVED_PASSWORD_MASK = "********"

@Composable
fun LoginScreen(authRepository: AuthRepository, padding: PaddingValues, currentUserId: String?, onSignedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var signedInUserId by remember { mutableStateOf(currentUserId) }
    // Re-read after Sign Out, which keeps the Remember me username and password.
    val savedUserId = remember(signedInUserId) { authRepository.savedUserId() }
    val hasSavedPassword = remember(signedInUserId) { authRepository.hasSavedPassword() }
    var userId by rememberSaveable { mutableStateOf(savedUserId.orEmpty()) }
    LaunchedEffect(savedUserId) { if (userId.isEmpty() && savedUserId != null) userId = savedUserId }
    var password by rememberSaveable { mutableStateOf("") }
    var remember by rememberSaveable { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var signingIn by remember { mutableStateOf(false) }
    // The saved password shows as dots without ever being loaded into the form; the first edit clears it.
    var keepSaved by remember(signedInUserId, hasSavedPassword) { mutableStateOf(hasSavedPassword) }
    val useSaved = keepSaved && userId.trim() == savedUserId

    Column(
        Modifier.fillMaxSize().background(MarksyTheme.Background)
            .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (signedInUserId != null) {
            Text("Signed in as ${signedInUserId}", color = MarksyTheme.PrimaryEmerald, fontSize = 14.sp)
            OutlinedButton(onClick = {
                scope.launch {
                    authRepository.logout()
                    signedInUserId = null
                }
            }) { Text("Sign Out", color = MarksyTheme.RedUrgent) }
            return@Column
        }

        Text(
            "Sign in with your Marksy account. This replaces any previously configured integration or market keys.",
            color = MarksyTheme.TextSecondary,
            fontSize = 13.sp
        )
        // CompactTextField renders `label` as a separate node above the field, outside its
        // own semantics boundary -- unreachable by a single onNodeWithText().performTextInput()
        // call. `placeholder` renders inside the field's decorationBox, part of the same merged
        // node as its input actions, so it is both a usable hint and a stable test target.
        CompactTextField(
            value = userId,
            onValueChange = { userId = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Marksy username"
        )
        CompactTextField(
            value = if (useSaved) SAVED_PASSWORD_MASK else password,
            onValueChange = { v ->
                if (useSaved) { keepSaved = false; password = v.removePrefix(SAVED_PASSWORD_MASK).takeIf { v.startsWith(SAVED_PASSWORD_MASK) }.orEmpty() }
                else password = v
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Password",
            visualTransformation = PasswordVisualTransformation()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = remember, onCheckedChange = { remember = it })
            Text("Remember me on this device", color = MarksyTheme.TextSecondary, fontSize = 13.sp)
        }
        Button(
            onClick = {
                if (signingIn) return@Button
                signingIn = true
                errorMessage = null
                scope.launch {
                    val result = if (useSaved) authRepository.loginWithSavedPassword(userId.trim(), remember)
                        else authRepository.login(userId.trim(), password, remember)
                    signingIn = false
                    result.onSuccess {
                        password = ""
                        signedInUserId = userId.trim()
                        onSignedIn()
                    }.onFailure { error ->
                        if (useSaved) keepSaved = authRepository.hasSavedPassword()
                        errorMessage = error.message ?: "Sign in failed. Try again."
                    }
                }
            },
            enabled = userId.isNotBlank() && (password.isNotBlank() || useSaved) && !signingIn,
            colors = ButtonDefaults.buttonColors(containerColor = MarksyTheme.PrimaryEmerald)
        ) { Text(if (signingIn) "Signing In..." else "Sign In", color = androidx.compose.ui.graphics.Color.Black) }
        errorMessage?.let { Text(it, color = MarksyTheme.RedUrgent, fontSize = 13.sp) }
    }
}
