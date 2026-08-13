# Notification Escalation Implementation

## Summary
This document describes the implementation of notification escalation for the task-reminder-app. Notifications now repeat/escalate until the task is marked done, respecting quiet hours, and feeling deliberately persistent.

## Changes Made

### 1. Database Migration
The following migration adds the necessary columns for tracking notification counts and quiet hours (already applied):

```sql
-- task-reminder-app/backend/supabase/migrations/0006_add_notification_escalation.sql
-- Add escalation tracking and quiet-hours support.
-- nag_interval_minutes already exists from 0001_init_schema.

alter table public.tasks
  add column if not exists notification_count integer not null default 0;

alter table public.devices
  add column if not exists quiet_hours_start time,
  add column if not exists quiet_hours_end time;

-- Recreate the index to match the new nag-based query pattern.
-- With repeated nags, last_notified_at is non-null, so the old
-- IS NULL predicate no longer covers all rows we need.
drop index if exists public.tasks_due_pending_idx;
create index if not exists tasks_due_pending_idx
  on public.tasks (due_at)
  where status = 'pending';
```

### 2. Backend Edge Function
The `check-due-tasks` Edge Function has been updated to support repeat notifications and quiet hours. Key changes:
- Uses per-task `nag_interval_minutes` for rescheduling (via post-query filtering)
- Implements quiet hours check (skipping notifications but not updating `last_notified_at` when in quiet hours)
- Increments `notification_count` only when a notification is successfully sent
- Handles group tasks by checking all members' devices

Here is the complete function:

```typescript
// task-reminder-app/backend/supabase/functions/check-due-tasks/index.ts
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.52.0";
import admin from "npm:firebase-admin@12";

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const supabaseServiceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const firebaseServiceAccount = Deno.env.get("FIREBASE_SERVICE_ACCOUNT") ?? "{}";

const supabase = createClient(supabaseUrl, supabaseServiceRoleKey, {
  db: { schema: "public" },
  auth: { persistSession: false },
});

if (!admin.apps.length) {
  admin.initializeApp({
    credential: admin.credential.cert(JSON.parse(firebaseServiceAccount)),
  });
}

const messaging = admin.messaging();

/**
 * Check if the current UTC time falls within a quiet-hours window.
 * Handles overnight ranges (e.g. 22:00 → 07:00).
 */
function isInQuietHours(
  quietStart: string | null | undefined,
  quietEnd: string | null | undefined,
): boolean {
  if (!quietStart || !quietEnd) {
    return false;
  }

  const now = new Date();
  const currentMinutes = now.getUTCHours() * 60 + now.getUTCMinutes();

  const parseTimeToMinutes = (timeStr: string): number => {
    const [h, m] = timeStr.split(":").map(Number);
    return h * 60 + m;
  };

  const startMinutes = parseTimeToMinutes(quietStart);
  const endMinutes = parseTimeToMinutes(quietEnd);

  if (startMinutes < endMinutes) {
    // Normal range, e.g. 09:00–17:00
    return currentMinutes >= startMinutes && currentMinutes < endMinutes;
  }

  if (startMinutes > endMinutes) {
    // Overnight range, e.g. 22:00 → 07:00 (spans midnight)
    return currentMinutes >= startMinutes || currentMinutes < endMinutes;
  }

  // start === end — degenerate case, treat as no quiet hours
  return false;
}

serve(async () => {
  try {
    const now = new Date();
    const nowIso = now.toISOString();
    // Fetch tasks that *might* be due for a nag. We use a generous 2-hour
    // cutoff because individual nag_interval_minutes values vary per task.
    // The precise per-task interval check happens in the loop below.
    const twoHoursAgo = new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString();

    const { data: dueTasks, error: tasksError } = await supabase
      .from("tasks")
      .select("id, title, due_at, owner_identity_id, group_id, nag_interval_minutes, notification_count, last_notified_at")
      .eq("status", "pending")
      .lte("due_at", nowIso)
      .or(`last_notified_at.is.null,last_notified_at.lte.${twoHoursAgo}`);

    if (tasksError) {
      throw tasksError;
    }

    if (!dueTasks || dueTasks.length === 0) {
      return new Response(
        JSON.stringify({ success: true, message: "No due tasks to notify." }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    }

    let notifiedCount = 0;
    let skippedCount = 0;
    let quietHoursSkipped = 0;
    let intervalSkipped = 0;

    for (const task of dueTasks) {
      // --- Per-task interval check (precise) ---
      // Only nag again if last_notified_at is null or
      // last_notified_at <= now() - nag_interval_minutes
      if (task.last_notified_at) {
        const lastNotified = new Date(task.last_notified_at).getTime();
        const nagIntervalMs = (task.nag_interval_minutes ?? 30) * 60 * 1000;
        if (Date.now() - lastNotified < nagIntervalMs) {
          intervalSkipped += 1;
          continue;
        }
      }

      let devices: {
        fcm_token: string | null;
        quiet_hours_start: string | null;
        quiet_hours_end: string | null;
      }[] = [];

      if (task.owner_identity_id) {
        // Personal task — fetch devices for this identity
        const { data: devData, error: devError } = await supabase
          .from("devices")
          .select("fcm_token, quiet_hours_start, quiet_hours_end")
          .eq("identity_id", task.owner_identity_id)
          .not("fcm_token", "is", null);

        if (devError) {
          console.error(
            `Error fetching devices for task ${task.id}:`,
            devError.message,
          );
          skippedCount += 1;
          continue;
        }

        devices = devData ?? [];
      } else if (task.group_id) {
        // Group task — fetch all devices belonging to group members
        const { data: groupMembers, error: membersError } = await supabase
          .from("group_members")
          .select("identity_id")
          .eq("group_id", task.group_id);

        if (membersError) {
          console.error(
            `Error fetching group members for task ${task.id}:`,
            membersError.message,
          );
          skippedCount += 1;
          continue;
        }

        const identityIds = (groupMembers ?? [])
          .map((m) => m.identity_id)
          .filter((id): id is string => id != null);

        if (identityIds.length === 0) {
          quietHoursSkipped += 1;
          continue;
        }

        const { data: devData, error: devError } = await supabase
          .from("devices")
          .select("fcm_token, quiet_hours_start, quiet_hours_end")
          .in("identity_id", identityIds)
          .not("fcm_token", "is", null);

        if (devError) {
          console.error(
            `Error fetching group devices for task ${task.id}:`,
            devError.message,
          );
          skippedCount += 1;
          continue;
        }

        devices = devData ?? [];
      }

      // --- Quiet hours check ---
      // If ALL devices are in quiet hours, skip the entire task
      // (don't update last_notified_at so it retries after quiet hours end)
      const allDevicesInQuietHours =
        devices.length > 0 &&
        devices.every((d) =>
          isInQuietHours(d.quiet_hours_start, d.quiet_hours_end)
        );

      if (allDevicesInQuietHours) {
        quietHoursSkipped += 1;
        continue; // skip — do NOT update last_notified_at
      }

      // Filter out individual devices that are in quiet hours
      const deliverableDevices = devices.filter((d) =>
        !isInQuietHours(d.quiet_hours_start, d.quiet_hours_end)
      );

      const tokens = deliverableDevices
        .map((d) => d.fcm_token)
        .filter((t): t is string => t != null && t.trim() !== "");

      if (tokens.length === 0) {
        console.log(
          `Task ${task.id}: no deliverable devices with FCM tokens, skipping.`,
        );
        // Still update last_notified_at since all devices are either missing tokens
        // or in quiet hours — we shouldn't retry immediately
        await supabase
          .from("tasks")
          .update({
            last_notified_at: nowIso,
            notification_count: (task.notification_count ?? 0) + 0,
          })
          .eq("id", task.id);
        skippedCount += 1;
        continue;
      }

      const currentCount = task.notification_count ?? 0;

      const messages: admin.messaging.Message[] = tokens.map((token) => ({
        token: token,
        data: {
          task_id: task.id,
          title: task.title,
          due_at: task.due_at ?? "",
          nag_count: String(currentCount + 1),
        },
        notification: {
          title: task.title,
          body: `This task is overdue. Nag #${currentCount + 1}`,
        },
        android: {
          priority: "high",
          notification: {
            sound: "default",
            click_action: "OPEN_ACTIVITY_1",
          },
        },
      }));

      const batchResponse = await messaging.sendEach(messages, {
        throwOnError: false,
      });

      const successTokens = batchResponse.responses.filter((r) => r.success);
      const failureTokens = batchResponse.responses.filter((r) => !r.success);

      if (failureTokens.length > 0) {
        console.error(
          `Task ${task.id}: ${failureTokens.length} FCM send failures:`,
          failureTokens.map((fr) => fr.error?.message).join(", "),
        );
      }

      const newCount = currentCount + (successTokens.length > 0 ? 1 : 0);

      if (successTokens.length > 0) {
        notifiedCount += 1;
        await supabase
          .from("tasks")
          .update({
            last_notified_at: nowIso,
            notification_count: newCount,
          })
          .eq("id", task.id);
      } else {
        skippedCount += 1;
      }
    }

    return new Response(
      JSON.stringify({
        success: true,
        tasks_checked: dueTasks.length,
        notified: notifiedCount,
        skipped: skippedCount,
        quiet_hours_skipped: quietHoursSkipped,
        interval_skipped: intervalSkipped,
      }),
      { status: 200, headers: { "Content-Type": "application/json" } },
    );
  } catch (error) {
    const errorMessage =
      error instanceof Error ? error.message : "Unknown error";
    console.error("check-due-tasks error:", errorMessage);
    return new Response(
      JSON.stringify({ success: false, error: errorMessage }),
      { status: 500, headers: { "Content-Type": "application/json" } },
    );
  }
});
```

### 3. Android Notification Service
The `TaskReminderFirebaseMessagingService.kt` already meets the requirements:
- Uses `taskId?.hashCode()` as the notification ID (ensuring repeat notifications replace previous ones)
- Sets notification importance to `NotificationManager.IMPORTANCE_HIGH`
- Enables sound by default via `setDefaults(DEFAULT_VIBRATE | DEFAULT_SOUND)`

No changes were needed to this file.

```kotlin
// task-reminder-app/android/app/src/main/java/com/example/taskreminder/notifications/TaskReminderFirebaseMessagingService.kt
package com.example.taskreminder.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.taskreminder.R
import com.example.taskreminder.data.DeviceIdStore
import com.example.taskreminder.data.SupabaseClientProvider
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter

class TaskReminderFirebaseMessagingService : FirebaseMessagingService() {

    private val deviceIdStore by lazy { DeviceIdStore(this) }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val deviceId = deviceIdStore.getDeviceId()
                val payload = JSONObject().apply {
                    put("fcm_token", token)
                }

                val connection = SupabaseClientProvider.openConnection(
                    "devices?id=eq.$deviceId", "PATCH"
                )
                try {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Prefer", "return=minimal")

                    OutputStreamWriter(connection.outputStream).use { writer ->
                        writer.write(payload.toString())
                    }

                    if (connection.responseCode !in 200..299) {
                        Log.e(
                            "FCM",
                            "Failed to update FCM token: ${connection.responseCode} ${SupabaseClientProvider.readBody(connection)}",
                        )
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.e("FCM", "Error syncing FCM token", e)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data
        val taskId = data["task_id"]
        val title = data["title"] ?: data["task_id"]
        val dueAt = data["due_at"]
        val nagCount = data["nag_count"]

        showNotification(taskId, title, dueAt, nagCount)
    }

    private fun showNotification(
        taskId: String?,
        title: String,
        dueAt: String?,
        nagCount: String?,
    ) {
        val channelId = "task_reminders"
        val channelName = "Task Reminders"

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH,
            )
            channel.enableVibration(true)
            channel.enableLights(true)
            notificationManager.createNotificationChannel(channel)
        }

        val contentText = if (dueAt != null) "Due: $dueAt" else "You have a pending reminder"
        val notificationTitle = if (nagCount != null && nagCount != "1") {
            "$title (Nag #$nagCount)"
        } else {
            title
        }

        val intent = Intent(this, com.example.taskreminder.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("task_id", taskId)
        }

        val notificationId = taskId?.hashCode() ?: System.currentTimeMillis().toInt()

        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notificationTitle)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        buildString {
                            append(notificationTitle)
                            if (dueAt != null) {
                                append("\nDue: $dueAt")
                            }
                        },
                    ),
            )
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(android.app.Notification.DEFAULT_VIBRATE or android.app.Notification.DEFAULT_SOUND)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(false)

        NotificationManagerCompat.from(this).notify(notificationId, builder.build())
    }
}
```

### 4. Android Quiet Hours UI
Added quiet hours settings to `SettingsScreen.kt`:
- Two time pickers for start and end time (in HH:mm format)
- Loads current quiet hours from the database for the device
- Saves quiet hours to the `devices` table via Supabase REST API
- Includes validation and feedback

See the full updated file at:
`task-reminder-app/android/app/src/main/java/com/example/taskreminder/ui/SettingsScreen.kt`

### 5. Web (PWA) Status
After checking the web directory (`task-reminder-app/web/`), there is no service worker or push notification implementation. Web push would require:
- Setting up VAPID keys
- Adding a service worker to handle push events
- Implementing permission requests and subscription management

This is noted as a gap for future work, but not implemented in this scope as the task focuses on the core mobile/backend features.

## Build and Deployment Notes
1. **Backend**: The Edge Function is deployed via Supabase. Ensure the Supabase project has the latest migration applied.
2. **Android**: The app builds successfully with the updated notification service and settings screen.
3. **Testing**: 
   - Verify repeat notifications respect `nag_interval_minutes`
   - Confirm quiet hours suppress notifications until the window ends
   - Check that notifications update in-place (same ID) rather than stacking
   - Ensure notification importance is HIGH and sound plays

## Future Enhancements
- Implement escalating intensity (e.g., shorter intervals after N notifications)
- Add web push support
- Refactor quiet hours storage to a dedicated `user_preferences` table for multi-device sync