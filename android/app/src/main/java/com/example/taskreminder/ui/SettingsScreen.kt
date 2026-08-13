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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.example.taskreminder.data.SupabaseClientProvider
import java.io.OutputStreamWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compose screen for notification and identity settings.
 * Shows device identity information and allows linking/unlinking devices.
 * Includes quiet hours settings.
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

    // State for quiet hours UI
    var quietHoursStart by rememberSaveable { mutableStateOf<String?>(null) }
    var quietHoursEnd by rememberSaveable { mutableStateOf<String?>(null) }
    var isLoadingQuietHours by rememberSaveable { mutableStateOf(false) }
    var quietHoursError by rememberSaveable { mutableStateOf<String?>(null) }
    var quietHoursSuccess by rememberSaveable { mutableStateOf<String?>(null) }
    var isSavingQuietHours by rememberSaveable { mutableStateOf(false) }

    // Get current identity ID
    var currentIdentityId by rememberSaveable { mutableStateOf<String?>(null) }
    var currentDeviceId by rememberSaveable { mutableStateOf<String?>(null) }

    // Initialize state
    var initialized by rememberSaveable { mutableStateOf(false) }

    // --- Quiet hours helper functions (defined before use) ---

    fun parseTimeField(response: String, fieldName: String): String? {
        if (response.isBlank() || response == "[]") return null
        val idx = response.indexOf(fieldName)
        if (idx < 0) return null
        val colonIdx = response.indexOf(':', idx + fieldName.length)
        if (colonIdx < 0) return null
        val valueStart = colonIdx + 1
        val valueEnd = response.indexOfFirst { it == ',' || it == '}' || it == ']' }
        val raw = response.substring(valueStart, valueEnd).trim(' ', '"')
        if (raw == "null") return null
        return raw
    }

    fun loadQuietHours(deviceId: String) {
        coroutineScope.launch {
            isLoadingQuietHours = true
            quietHoursError = null
            try {
                val connection = SupabaseClientProvider.openConnection(
                    "devices?id=eq.$deviceId&select=quiet_hours_start,quiet_hours_end",
                    "GET",
                )
                try {
                    if (connection.responseCode in 200..299) {
                        val response = SupabaseClientProvider.readBody(connection)
                        quietHoursStart = parseTimeField(response, "quiet_hours_start")
                        quietHoursEnd = parseTimeField(response, "quiet_hours_end")
                    } else {
                        quietHoursError = "Failed to load quiet hours: ${connection.responseCode}"
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                quietHoursError = "Error loading quiet hours: ${e.message}"
            } finally {
                isLoadingQuietHours = false
            }
        }
    }

    fun saveQuietHours(deviceId: String) {
        val start = quietHoursStart ?: "null"
        val end = quietHoursEnd ?: "null"
        val payload = """{"quiet_hours_start":$start,"quiet_hours_end":$end}""".trimIndent()

        coroutineScope.launch {
            isSavingQuietHours = true
            quietHoursError = null
            quietHoursSuccess = null
            try {
                val connection = SupabaseClientProvider.openConnection(
                    "devices?id=eq.$deviceId",
                    "PATCH",
                )
                try {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")

                    OutputStreamWriter(connection.outputStream).use { writer ->
                        writer.write(payload)
                    }

                    if (connection.responseCode in 200..299) {
                        quietHoursSuccess = "Quiet hours saved successfully"
                    } else {
                        val errorBody = SupabaseClientProvider.readBody(connection)
                        quietHoursError = "Failed to save quiet hours: ${connection.responseCode} $errorBody"
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                quietHoursError = "Error saving quiet hours: ${e.message}"
            } finally {
                isSavingQuietHours = false
            }
        }
    }

    // Load identity ID, device ID, and quiet hours on first composition
    if (!initialized) {
        initialized = true
        coroutineScope.launch {
            try {
                val identityId = deviceIdStore.getIdentityId()
                val deviceId = deviceIdStore.getDeviceId()
                currentIdentityId = identityId
                currentDeviceId = deviceId
                loadQuietHours(deviceId)
            } catch (e: Exception) {
                Log.e("SettingsScreen", "Failed to load identity/device", e)
                currentIdentityId = null
                currentDeviceId = null
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

        // Quiet Hours Settings Card
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
                    text = "Quiet Hours",
                    style = MaterialTheme.typography.titleMedium
                )
                if (isLoadingQuietHours) {
                    Text(
                        text = "Loading quiet hours...",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (quietHoursError != null) {
                    Text(
                        text = quietHoursError!!,
                        color = Color(0xFFD32F2F), // Red
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    val startValue = quietHoursStart ?: ""
                    val endValue = quietHoursEnd ?: ""
                    val startTimeValid = startValue.isEmpty() || startValue.matches(Regex("[0-9]{2}:[0-9]{2}"))
                    val endTimeValid = endValue.isEmpty() || endValue.matches(Regex("[0-9]{2}:[0-9]{2}"))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Start:",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        OutlinedTextField(
                            value = startValue,
                            onValueChange = { quietHoursStart = if (it.isBlank()) null else it },
                            label = { Text("HH:mm") },
                            modifier = Modifier
                                .width(80.dp),
                            isError = !startTimeValid,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            )
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "End:",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        OutlinedTextField(
                            value = endValue,
                            onValueChange = { quietHoursEnd = if (it.isBlank()) null else it },
                            label = { Text("HH:mm") },
                            modifier = Modifier
                                .width(80.dp),
                            isError = !endTimeValid,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            )
                        )
                    }
                    if (quietHoursSuccess != null && !isSavingQuietHours) {
                        Text(
                            text = quietHoursSuccess!!,
                            color = Color(0xFF00C853), // Green
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(
                        onClick = {
                            if (startTimeValid && endTimeValid) {
                                val deviceId = currentDeviceId
                                if (deviceId != null) {
                                    saveQuietHours(deviceId)
                                } else {
                                    quietHoursError = "Device ID not available"
                                }
                            } else {
                                quietHoursError = "Please enter valid times in HH:mm format"
                            }
                        },
                        enabled = !isSavingQuietHours
                    ) {
                        if (isSavingQuietHours) {
                            Text("Saving...")
                        } else {
                            Text("Save Quiet Hours")
                        }
                    }
                }
                Text(
                    text = "Set quiet hours to suppress notifications during specific times. Use HH:mm format (e.g. 22:00). Overnight ranges like 22:00–07:00 are supported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF757575) // Gray
                )
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
                Text(
                    text = "Notification settings, etc. coming soon...",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF757575) // Gray
                )
            }
        }
    }
}
