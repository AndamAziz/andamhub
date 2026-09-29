package uk.andam.app.ui

import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.andam.app.auth.Session

@Composable
fun LoginScreen(message: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember(message) { mutableStateOf(message) }

    fun openOAuth(provider: String) {
        val tab = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(C.Bg.toArgb()).build())
            .build()
        tab.launchUrl(context, Uri.parse(Session.oauthUrl(provider)))
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = C.Surface, unfocusedContainerColor = C.Surface,
        focusedBorderColor = C.Ember, unfocusedBorderColor = C.Hair, cursorColor = C.Ember,
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(C.Bg)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        BrandMark(64)
        Spacer(Modifier.height(18.dp))
        Text("Welcome to Andam", style = MaterialTheme.typography.headlineSmall)
        Text("Live TV, movies and series", color = C.Muted, modifier = Modifier.padding(top = 6.dp, bottom = 28.dp))

        Button(
            onClick = { openOAuth("google") },
            colors = ButtonDefaults.buttonColors(containerColor = C.Text, contentColor = C.Bg),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Continue with Google") }

        Text("or", color = C.Faint, modifier = Modifier.padding(vertical = 16.dp))

        OutlinedTextField(
            value = email, onValueChange = { email = it }, singleLine = true,
            label = { Text("Email") }, colors = fieldColors, shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it }, singleLine = true,
            label = { Text("Password") }, colors = fieldColors, shape = RoundedCornerShape(14.dp),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = C.Ember, modifier = Modifier.padding(top = 10.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        Session.signIn(email, password)
                        onDone()
                    } catch (e: Exception) {
                        error = e.message ?: "Sign-in failed"
                    }
                    busy = false
                }
            },
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = C.Ember),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (busy) "Signing in…" else "Sign in") }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { Session.continueAsGuest(); onDone() },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text("Continue without an account (IPTV)", color = C.Muted) }

        TextButton(onClick = {
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse("${uk.andam.app.Config.BASE_URL}/auth"))
        }) { Text("Create an account or reset password", color = C.Faint) }
        Spacer(Modifier.height(24.dp))
    }
}
