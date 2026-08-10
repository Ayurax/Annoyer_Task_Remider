package com.example.taskreminder

import android.os.Bundle
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextFieldColors
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import com.example.taskreminder.ui.SettingsScreen
import com.example.taskreminder.data.DeviceIdStore
import com.example.taskreminder.data.Group
import com.example.taskreminder.data.GroupRepository
import com.example.taskreminder.data.OfflineSyncCoordinator
import com.example.taskreminder.data.TaskRecord
import com.example.taskreminder.data.TaskRepository
import com.example.taskreminder.ui.AddTaskScreen
import com.example.taskreminder.ui.TaskListScreen
import com.example.taskreminder.ui.theme.TaskReminderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()

        val offlineSyncCoordinator = OfflineSyncCoordinator(this)
        lifecycleScope.launch(Dispatchers.IO) {
            offlineSyncCoordinator.manuallySync()
        }

        setContent {
            TaskReminderTheme {
                TaskReminderApp()
            }
        }
    }

    private enum class AppTab {
        Tasks,
        AddTask,
        Settings
    }

    @Composable
    private fun TaskReminderApp() {
        val context = LocalContext.current.applicationContext
        val taskRepository = remember { TaskRepository(context) }
        val groupRepository = remember { GroupRepository(context) }
        val deviceIdStore = remember { DeviceIdStore(context) }
        val coroutineScope = rememberCoroutineScope()

        var selectedTab by rememberSaveable { mutableStateOf(AppTab.Tasks) }
        var activeGroupId by rememberSaveable { mutableStateOf<String?>(null) }
        var tasks by remember { mutableStateOf<List<TaskRecord>>(emptyList()) }
        var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
        var currentIdentityId by remember { mutableStateOf<String?>(null) }
        var isLoading by remember { mutableStateOf(true) }
        var isGroupsLoading by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var groupsError by remember { mutableStateOf<String?>(null) }
        var refreshKey by rememberSaveable { mutableStateOf(0) }

        fun refreshTasks() {
            coroutineScope.launch {
                isLoading = true
                errorMessage = null
                try {
                    tasks = taskRepository.fetchPendingTasks(activeGroupId, currentIdentityId)
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Failed to load tasks."
                } finally {
                    isLoading = false
                }
            }
        }

        fun loadGroups() {
            coroutineScope.launch {
                isGroupsLoading = true
                groupsError = null
                try {
                    currentIdentityId = deviceIdStore.getIdentityId()
                    groups = groupRepository.getMyGroups()
                    if (activeGroupId != null && groups.none { it.id == activeGroupId }) {
                        activeGroupId = null
                    }
                } catch (e: Exception) {
                    groupsError = e.message ?: "Failed to load groups."
                } finally {
                    isGroupsLoading = false
                }
            }
        }

        LaunchedEffect(refreshKey, activeGroupId) {
            refreshTasks()
            loadGroups()
        }

        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Task Reminder",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    GroupScopeSelector(
                        activeGroupId = activeGroupId,
                        groups = groups,
                        isLoading = isGroupsLoading,
                        onGroupSelected = { activeGroupId = it },
                    )
                }
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Button(
                            onClick = { selectedTab = AppTab.Tasks },
                            shape = RectangleShape,
                        ) {
                            Text("Tasks")
                        }
                        Button(
                            onClick = { selectedTab = AppTab.AddTask },
                            shape = RectangleShape,
                        ) {
                            Text("Add task")
                        }
                        Button(
                            onClick = { selectedTab = AppTab.Settings },
                            shape = RectangleShape,
                        ) {
                            Text("Settings")
                        }
                    }
                }
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                GroupManagementPanel(
                    groups = groups,
                    currentIdentityId = currentIdentityId,
                    isLoading = isGroupsLoading,
                    errorMessage = groupsError,
                    onCreateGroup = { name ->
                        coroutineScope.launch {
                            try {
                                val group = groupRepository.createGroup(name)
                                activeGroupId = group.id
                                refreshKey += 1
                            } catch (e: Exception) {
                                groupsError = e.message ?: "Failed to create group."
                            }
                        }
                    },
                    onJoinGroup = { joinCode ->
                        coroutineScope.launch {
                            try {
                                val group = groupRepository.joinGroup(joinCode)
                                if (group != null) {
                                    activeGroupId = group.id
                                    refreshKey += 1
                                } else {
                                    groupsError = "Invalid join code."
                                }
                            } catch (e: Exception) {
                                groupsError = e.message ?: "Failed to join group."
                            }
                        }
                    },
                    onLeaveGroup = { groupId ->
                        coroutineScope.launch {
                            try {
                                groupRepository.leaveGroup(groupId)
                                if (activeGroupId == groupId) {
                                    activeGroupId = null
                                }
                                refreshKey += 1
                            } catch (e: Exception) {
                                groupsError = e.message ?: "Failed to leave group."
                            }
                        }
                    },
                    onDeleteGroup = { groupId ->
                        coroutineScope.launch {
                            try {
                                groupRepository.deleteGroup(groupId)
                                if (activeGroupId == groupId) {
                                    activeGroupId = null
                                }
                                refreshKey += 1
                            } catch (e: Exception) {
                                groupsError = e.message ?: "Failed to delete group."
                            }
                        }
                    },
                )

                // Device identity management is available in Settings
                Text(
                    text = "Manage device identity in Settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF0066CC),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                when (selectedTab) {
                    AppTab.Tasks -> TaskListScreen(
                        tasks = tasks,
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        onRefresh = { refreshKey += 1 },
                        onMarkDone = { localId ->
                            coroutineScope.launch {
                                try {
                                    taskRepository.markTaskDone(localId)
                                    refreshKey += 1
                                } catch (e: Exception) {
                                    errorMessage = e.message ?: "Failed to complete task."
                                }
                            }
                        },
                    )

                    AppTab.AddTask -> AddTaskScreen(
                        onAddTask = { request ->
                            coroutineScope.launch {
                                try {
                                    taskRepository.insertTask(
                                        title = request.title,
                                        notes = request.notes,
                                        dueAt = request.dueAt,
                                        nagIntervalMinutes = request.nagIntervalMinutes,
                                        groupId = request.groupId,
                                    )
                                    refreshKey += 1
                                    selectedTab = AppTab.Tasks
                                } catch (e: Exception) {
                                    errorMessage = e.message ?: "Failed to add task."
                                }
                            }
                        },
                        groupRepository = groupRepository,
                    )

                    AppTab.Settings -> SettingsScreen(
                        context = LocalContext.current,
                        deviceIdStore = deviceIdStore,
                        groupRepository = groupRepository,
                        coroutineScope = rememberCoroutineScope()
                    )
                }
            }
        }
    }

    @Composable
    private fun IdentityLinkingSection(
        linkCodeState: String?,
        linkCodeErrorState: String?,
        isLoadingLinkCodeState: Boolean,
        enteredLinkCodeState: String,
        isLinkingState: Boolean,
        linkErrorState: String?,
        linkSuccessState: String?,
        currentIdentityId: String?,
        groupRepository: GroupRepository,
        coroutineScope: CoroutineScope,
        onGetLinkCode: (String) -> Unit,
        onLinkCodeError: (String) -> Unit,
        onLinkingStarted: () -> Unit,
        onLinkingFinished: () -> Unit,
        onLinkSuccess: (String, String) -> Unit,
        onLinkError: (String) -> Unit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Device Identity",
                style = MaterialTheme.typography.titleMedium,
            )

            // Get or display link code
            LaunchedEffect(Unit, currentIdentityId) {
                if (currentIdentityId != null && linkCodeState == null) {
                    onLinkingStarted()
                    coroutineScope.launch {
                        try {
                            val code = groupRepository.getOrCreateIdentityLinkCode()
                            onGetLinkCode(code)
                        } catch (e: Exception) {
                            onLinkCodeError("Failed to get identity link code: ${e.message}")
                        } finally {
                            onLinkingFinished()
                        }
                    }
                }
            }

            if (isLoadingLinkCodeState) {
                Text("Loading your identity link code...")
            } else if (linkCodeState != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Your sync code:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = linkCodeState,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                        )
                        Button(
                            onClick = {
                                // Copy to clipboard logic would go here
                                // For now, we'll just show a toast-like message
                                linkCodeState?.let { code ->
                                    onLinkSuccess("Code copied to clipboard (implementation pending)", code)
                                }
                            },
                        ) {
                            Text("Copy")
                        }
                    }
                    if (linkSuccessState != null) {
                        Text(
                            text = linkSuccessState,
                            color = Color(0xFF00C853), // Green success color
                        )
                    }
                }
            } else if (linkCodeErrorState != null) {
                Text(
                    text = linkCodeErrorState,
                    color = Color(0xFFD32F2F), // Red error color
                )
            }

            Divider()

            Text(
                text = "Link to existing identity",
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Local state for the entered link code
                var enteredLinkCode by rememberSaveable { mutableStateOf(enteredLinkCodeState) }
                OutlinedTextField(
                    value = enteredLinkCode,
                    onValueChange = { enteredLinkCode = it.uppercase() },
                    label = { Text("Enter link code") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    isError = linkErrorState != null,
                )
                Button(
                    onClick = {
                        if (enteredLinkCode.isNotBlank()) {
                            onLinkingStarted()
                            coroutineScope.launch {
                                try {
                                    val resultCode = groupRepository.linkDeviceToIdentity(enteredLinkCode)
                                    onLinkSuccess("Successfully linked to identity with code: $resultCode", resultCode)
                                } catch (e: Exception) {
                                    onLinkError("Failed to link: ${e.message}")
                                } finally {
                                    onLinkingFinished()
                                }
                            }
                        }
                    },
                ) {
                    if (isLinkingState) {
                        Text("Linking...")
                    } else {
                        Text("Link Device")
                    }
                }
            }

            if (linkErrorState != null) {
                Text(
                    text = linkErrorState,
                    color = Color(0xFFD32F2F), // Red error color
                )
            }
            if (linkSuccessState != null && !isLinkingState) {
                Text(
                    text = linkSuccessState,
                    color = Color(0xFF00C853), // Green success color
                )
            }
        }
    }

    @Composable
    private fun GroupScopeSelector(
        activeGroupId: String?,
        groups: List<Group>,
        isLoading: Boolean,
        onGroupSelected: (String?) -> Unit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (isLoading) "Loading groups..." else "Active list",
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onGroupSelected(null) },
                    shape = RectangleShape,
                    enabled = activeGroupId != null,
                ) {
                    Text("Personal")
                }
                groups.forEach { group ->
                    Button(
                        onClick = { onGroupSelected(group.id) },
                        shape = RectangleShape,
                        enabled = activeGroupId != group.id,
                    ) {
                        Text(group.name ?: group.joinCode)
                    }
                }
            }
        }
    }

    @Composable
    private fun GroupManagementPanel(
        groups: List<Group>,
        currentIdentityId: String?,
        isLoading: Boolean,
        errorMessage: String?,
        onCreateGroup: (String) -> Unit,
        onJoinGroup: (String) -> Unit,
        onLeaveGroup: (String) -> Unit,
        onDeleteGroup: (String) -> Unit,
    ) {
        var isExpanded by rememberSaveable { mutableStateOf(false) }
        var groupName by rememberSaveable { mutableStateOf("") }
        var joinCode by rememberSaveable { mutableStateOf("") }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Manage groups",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { isExpanded = !isExpanded },
                    shape = RectangleShape,
                ) {
                    Text(if (isExpanded) "Hide" else "Show")
                }
            }

            if (!isExpanded) {
                return@Column
            }

            if (isLoading) {
                Text("Loading groups...")
            }

            errorMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }

            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("New group name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = {
                    onCreateGroup(groupName)
                    groupName = ""
                },
                shape = RectangleShape,
                enabled = groupName.isNotBlank(),
            ) {
                Text("Create group")
            }

            Divider()

            OutlinedTextField(
                value = joinCode,
                onValueChange = { joinCode = it.uppercase() },
                label = { Text("Join code") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = {
                    onJoinGroup(joinCode)
                    joinCode = ""
                },
                shape = RectangleShape,
                enabled = joinCode.isNotBlank(),
            ) {
                Text("Join group")
            }

            Divider()

            if (groups.isEmpty()) {
                Text("You're not a member of any groups yet.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(groups, key = { it.id }) { group ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "${group.name ?: group.joinCode} (${group.joinCode})",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { onLeaveGroup(group.id) },
                                        shape = RectangleShape,
                                    ) {
                                        Text("Leave")
                                    }
                                    if (group.createdByIdentityId == currentIdentityId) {
                                        Button(
                                            onClick = { onDeleteGroup(group.id) },
                                            shape = RectangleShape,
                                        ) {
                                            Text("Delete")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Divider()
        }
    }
}