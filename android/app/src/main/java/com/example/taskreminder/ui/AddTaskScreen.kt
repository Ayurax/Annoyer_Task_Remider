package com.example.taskreminder.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.icu.util.Calendar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.taskreminder.data.Group
import com.example.taskreminder.data.GroupRepository
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class AddTaskRequest(
    val title: String,
    val notes: String?,
    val dueAt: String,
    val nagIntervalMinutes: Int,
    val groupId: String? = null, // Null means personal task
)

@Composable
fun AddTaskScreen(
    onAddTask: (AddTaskRequest) -> Unit,
    groupRepository: GroupRepository
) {
    val context = LocalContext.current
    var title by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var nagIntervalMinutes by rememberSaveable { mutableStateOf("30") }
    var dueDateTime by remember { mutableStateOf<LocalDateTime?>(null) }
    var titleError by remember { mutableStateOf<String?>(null) }
    var dueDateError by remember { mutableStateOf<String?>(null) }
    var selectedGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var isGroupsLoading by remember { mutableStateOf(true) }
    var groupsError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val dateFormatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm") }

    fun loadGroups() {
        coroutineScope.launch {
            isGroupsLoading = true
            groupsError = null
            try {
                groups = groupRepository.getMyGroups()
            } catch (e: Exception) {
                groupsError = e.message ?: "Failed to load groups."
            }
            isGroupsLoading = false
        }
    }

    LaunchedEffect(Unit) {
        if (dueDateTime == null) {
            dueDateTime = LocalDateTime.now().plusHours(1)
        }
        loadGroups()
    }

    fun pickDateTime() {
        val now = Calendar.getInstance()
        val current = dueDateTime ?: LocalDateTime.now().plusHours(1)

        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                TimePickerDialog(
                    context,
                    { _, hourOfDay, minute ->
                        dueDateTime = LocalDateTime.of(year, month + 1, dayOfMonth, hourOfDay, minute)
                        dueDateError = null
                    },
                    current.hour,
                    current.minute,
                    true,
                ).show()
            },
            current.year,
            current.monthValue - 1,
            current.dayOfMonth,
        ).apply {
            datePicker.minDate = now.timeInMillis
        }.show()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Add task", style = MaterialTheme.typography.headlineSmall)

        // Group selection section
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Assign to", style = MaterialTheme.typography.titleMedium)

            if (isGroupsLoading) {
                Text("Loading groups...")
            } else {
                groupsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                // Personal option + group options in a column
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Personal option (radio button style)
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedGroupId == null,
                            onClick = { selectedGroupId = null },
                            enabled = true
                        )
                        Text(
                            text = "Personal",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)

                    // Group options
                    if (groups.isEmpty()) {
                        Text("You're not a member of any groups yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        groups.forEach { group ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedGroupId == group.id,
                                    onClick = { selectedGroupId = group.id },
                                    enabled = true
                                )
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "${group.name ?: group.joinCode}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "(${group.joinCode})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = title,
            onValueChange = {
                title = it
                titleError = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Title") },
            isError = titleError != null,
            singleLine = true,
        )

        titleError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Notes") },
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Due date and time")
            Button(
                onClick = { pickDateTime() },
                shape = RectangleShape,
            ) {
                Text(text = dueDateTime?.format(dateFormatter) ?: "Pick due date and time")
            }
            dueDateError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        OutlinedTextField(
            value = nagIntervalMinutes,
            onValueChange = { nagIntervalMinutes = it.filter(Char::isDigit) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Nag interval in minutes") },
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                val trimmedTitle = title.trim()
                val dueAt = dueDateTime
                val nagMinutes = nagIntervalMinutes.toIntOrNull()

                titleError = if (trimmedTitle.isBlank()) "Give the task a title." else null
                dueDateError = if (dueAt == null) "Pick a due date and time." else null

                if (trimmedTitle.isBlank() || dueAt == null || nagMinutes == null || nagMinutes < 1) {
                    if (nagMinutes == null || nagMinutes < 1) {
                        return@Button
                    }
                    return@Button
                }

                onAddTask(
                    AddTaskRequest(
                        title = trimmedTitle,
                        notes = notes.trim().takeIf { it.isNotBlank() },
                        dueAt = dueAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        nagIntervalMinutes = nagMinutes,
                        groupId = selectedGroupId
                    ),
                )
                title = ""
                notes = ""
                nagIntervalMinutes = "30"
                dueDateTime = LocalDateTime.now().plusHours(1)
                selectedGroupId = null // Reset to personal after adding task
            }) {
                Text("Add task")
            }
        }
    }
}
