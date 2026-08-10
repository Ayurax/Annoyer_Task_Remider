package com.example.taskreminder.ui

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.taskreminder.data.DeviceIdStore
import com.example.taskreminder.data.GroupRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compose screen for notification and identity settings.
 * Shows device identity information and allows linking/unlinking devices.
 */
@Composable
fun SettingsScreen(
    context: Context,
    deviceIdStore: DeviceIdStore,
    groupRepository: GroupRepository,
    coroutineScope: CoroutineScope
) {
    // State for identity linking UI
    var linkCode by rememberSaveable { mutableStateOf<String?>(null) }
    var linkCodeError by rememberSaveable { mutableStateOf<String?>(null) }
    var isLoadingLinkCode by rememberSaveable { mutableStateOf(false) }
    var enteredLinkCode by rememberSaveable { mutableStateOf("") }
    var isLinking by rememberSaveable { mutableStateOf(false) }
    var linkError by rememberSaveable { mutableStateOf<String?>(null) }
    var linkSuccess by rememberSaveable { mutableStateOf<String?>(null) }

    // Get current identity ID
    var currentIdentityId by rememberSaveable { mutableStateOf<String?>(null) }

    // Initialize state
    var initialized by rememberSaveable { mutableStateOf(false) }

    // Load identity ID on first composition
    if (!initialized) {
        initialized = true
        coroutineScope.launch {
            try {
                val identityId = deviceIdStore.getIdentityId()
                currentIdentityId = identityId
            } catch (e: Exception) {
                Log.e("SettingsScreen", "Failed to load identity", e)
                currentIdentityId = null
                linkCodeError = "Failed to load identity: ${e.message}"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "Device Identity",
            style = MaterialTheme.typography.titleMedium
        )

        // Card for identity linking section
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            colors = CardDefaults.cardColors()
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Get or display link code section
                if (isLoadingLinkCode) {
                    Text(
                        text = "Loading your identity link code...",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (linkCode != null) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Your sync code:",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = linkCode!!,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Button(
                                onClick = {
                                    // Copy to clipboard
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setText(linkCode!!)
                                    linkSuccess = "Code copied to clipboard"
                                }
                            ) {
                                Text("Copy")
                            }
                        }
                        if (linkSuccess != null) {
                            Text(
                                text = linkSuccess!!,
                                color = Color(0xFF00C853), // Green
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                } else if (linkCodeError != null) {
                    Text(
                        text = linkCodeError!!,
                        color = Color(0xFFD32F2F), // Red
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    // Attempt to get link code when we have an identity
                    if (currentIdentityId != null) {
                        Text(
                            text = "Getting your identity link code...",
                            style = MaterialTheme.typography.bodySmall
                        )
                        // Launch coroutine to get link code
                        coroutineScope.launch {
                            isLoadingLinkCode = true
                            try {
                                linkCode = groupRepository.getOrCreateIdentityLinkCode()
                            } catch (e: Exception) {
                                linkCodeError = "Failed to get identity link code: ${e.message}"
                            } finally {
                                isLoadingLinkCode = false
                            }
                        }
                    } else {
                        Text(
                            text = "No device identity found",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Divider(
                    modifier = Modifier
                        .fillMaxWidth()
                )

                // Link to existing identity section
                Text(
                    text = "Link to existing identity",
                    style = MaterialTheme.typography.bodyLarge
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = enteredLinkCode,
                        onValueChange = { enteredLinkCode = it.uppercase() },
                        label = { Text("Enter link code") },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        singleLine = true,
                        isError = linkError != null,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            capitalization = KeyboardCapitalization.Characters
                        )
                    )
                    Button(
                        onClick = {
                            if (enteredLinkCode.isNotBlank()) {
                                isLinking = true
                                linkError = null
                                linkSuccess = null
                                coroutineScope.launch {
                                    try {
                                        val resultCode = groupRepository.linkDeviceToIdentity(enteredLinkCode)
                                        linkSuccess = "Successfully linked to identity with code: $resultCode"
                                        // Update our link code display
                                        linkCode = resultCode
                                        enteredLinkCode = ""
                                        // Refresh state
                                    } catch (e: Exception) {
                                        linkError = "Failed to link: ${e.message}"
                                    } finally {
                                        isLinking = false
                                    }
                                }
                            }
                        },
                        enabled = enteredLinkCode.isNotBlank() && !isLinking
                    ) {
                        if (isLinking) {
                            Text("Linking...")
                        } else {
                            Text("Link Device")
                        }
                    }
                }

                if (linkError != null) {
                    Text(
                        text = linkError!!,
                        color = Color(0xFFD32F2F), // Red
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (linkSuccess != null && !isLinking) {
                    Text(
                        text = linkSuccess!!,
                        color = Color(0xFF00C853), // Green
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Additional settings sections can go here
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            colors = CardDefaults.cardColors()
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Additional Settings",
                    style = MaterialTheme.typography.titleMedium
                )
                Divider(
                    modifier = Modifier
                        .fillMaxWidth()
                )
                // Placeholder for other settings
                Text(
                    text = "Notification settings, quiet hours, etc. coming soon...",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF757575) // Gray
                )
            }
        }
    }
}