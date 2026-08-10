package com.example.taskreminder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.example.taskreminder.data.TaskRecord

@Composable
fun TaskListScreen(
    tasks: List<TaskRecord>,
    isLoading: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onMarkDone: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Pending tasks", style = MaterialTheme.typography.headlineSmall)
            Button(
                onClick = onRefresh,
                shape = RectangleShape,
            ) {
                Text("Refresh")
            }
        }

        if (isLoading) {
            Text("Loading tasks...")
        }

        errorMessage?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        if (!isLoading && tasks.isEmpty()) {
            Text("No pending tasks right now.")
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(tasks, key = { it.id.orEmpty() }) { task ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = task.title, style = MaterialTheme.typography.titleMedium)
                        Text(text = "Due ${task.dueAt}", style = MaterialTheme.typography.bodyMedium)
                        task.notes?.takeIf { it.isNotBlank() }?.let {
                            Text(text = it, style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(
                            onClick = { task.id?.let(onMarkDone) },
                            shape = RectangleShape,
                        ) {
                            Text("Mark done")
                        }
                    }
                }
            }
        }
    }
}
