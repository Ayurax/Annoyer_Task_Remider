import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.52.0";
import admin from "npm:firebase-admin@12";
import webPush from "https://esm.sh/web-push@3.6.7";

const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
const supabaseServiceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const firebaseServiceAccount = Deno.env.get("FIREBASE_SERVICE_ACCOUNT") ?? "{}";

const supabase = createClient(supabaseUrl, supabaseServiceRoleKey, {
  db: { schema: "public" },
  auth: { persistSession: false },
});

// --- Firebase Admin (Android FCM) ---
let firebaseMessaging: admin.messaging.Messaging | null = null;
try {
  if (!admin.apps.length) {
    admin.initializeApp({
      credential: admin.credential.cert(JSON.parse(firebaseServiceAccount)),
    });
  }
  firebaseMessaging = admin.messaging();
} catch (e) {
  console.error("Firebase Admin init failed:", (e as Error).message);
}

// --- Web Push (VAPID) ---
const vapidPublicKey = Deno.env.get("VAPID_PUBLIC_KEY") ?? "";
const vapidPrivateKey = Deno.env.get("VAPID_PRIVATE_KEY") ?? "";
const vapidSubject = "mailto:task-reminder@example.com";

const hasVapidKeys = vapidPublicKey !== "" && vapidPrivateKey !== "";
if (hasVapidKeys) {
  webPush.setVapidDetails(vapidSubject, vapidPublicKey, vapidPrivateKey);
}

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
    return currentMinutes >= startMinutes && currentMinutes < endMinutes;
  }

  if (startMinutes > endMinutes) {
    return currentMinutes >= startMinutes || currentMinutes < endMinutes;
  }

  return false;
}

/**
 * Send a web push notification to a single subscription.
 * Returns true on success, false on failure.
 */
async function sendWebPush(
  subscription: { endpoint: string; p256dh: string; auth: string },
  payload: Record<string, unknown>,
): Promise<boolean> {
  if (!hasVapidKeys) {
    return false;
  }

  try {
    await webPush.sendNotification(
      {
        endpoint: subscription.endpoint,
        keys: {
          p256dh: subscription.p256dh,
          auth: subscription.auth,
        },
      },
      JSON.stringify(payload),
      { TTL: 60 },
    );
    return true;
  } catch (e: unknown) {
    const status = (e as { statusCode?: number }).statusCode;
    // 404 / 410 / 411 means the subscription is stale — remove it
    if (status === 404 || status === 410 || status === 411) {
      console.log(
        `Stale subscription for endpoint ${subscription.endpoint.substring(0, 60)}…, removing.`,
      );
      await supabase
        .from("push_subscriptions")
        .delete()
        .eq("endpoint", subscription.endpoint);
    } else {
      console.error(`Web push error:`, (e as Error).message);
    }
    return false;
  }
}

serve(async (req) => {
  // --- Auth guard: require a secret token to prevent unauthorized invocation ---
  const authHeader = req.headers.get("authorization");
  const expectedToken = Deno.env.get("CHECK_DUE_TASKS_TOKEN") ?? "";
  if (expectedToken && authHeader !== `Bearer ${expectedToken}`) {
    return new Response(
      JSON.stringify({ success: false, error: "Unauthorized" }),
      { status: 401, headers: { "Content-Type": "application/json" } },
    );
  }

  try {
    const now = new Date();
    const nowIso = now.toISOString();
    // Fetch tasks that *might* be due for a nag. We use a generous 2-hour
    // safety window because individual nag_interval_minutes values vary.
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

      // --- Fetch devices with FCM tokens ---
      if (task.owner_identity_id) {
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

      // --- Quiet hours check (applies to both FCM and web push) ---
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

      const fcmTokens = deliverableDevices
        .map((d) => d.fcm_token)
        .filter((t): t is string => t != null && t.trim() !== "");

      // --- Fetch web push subscriptions ---
      let pushSubscriptions: {
        endpoint: string;
        p256dh: string;
        auth: string;
      }[] = [];

      const identityIds =
        task.owner_identity_id != null ? [task.owner_identity_id] : [];

      if (identityIds.length > 0) {
        const { data: subs, error: subsError } = await supabase
          .from("push_subscriptions")
          .select("endpoint, p256dh, auth")
          .in("identity_id", identityIds);

        if (subsError) {
          console.error(
            `Error fetching push subscriptions for task ${task.id}:`,
            subsError.message,
          );
        } else {
          pushSubscriptions = subs ?? [];
        }
      } else if (task.group_id) {
        const { data: groupMembers, error: membersError } = await supabase
          .from("group_members")
          .select("identity_id")
          .eq("group_id", task.group_id);

        if (!membersError && groupMembers) {
          const ids = groupMembers.map((m) => m.identity_id).filter(Boolean);
          if (ids.length > 0) {
            const { data: subs, error: subsError } = await supabase
              .from("push_subscriptions")
              .select("endpoint, p256dh, auth")
              .in("identity_id", ids);

            if (subsError) {
              console.error(
                `Error fetching group push subscriptions for task ${task.id}:`,
                subsError.message,
              );
            } else {
              pushSubscriptions = subs ?? [];
            }
          }
        }
      }

      const currentCount = task.notification_count ?? 0;
      const nagNumber = currentCount + 1;
      const didAnyDelivery = fcmTokens.length > 0 || pushSubscriptions.length > 0;

      if (!didAnyDelivery) {
        console.log(
          `Task ${task.id}: no FCM tokens or push subscriptions, skipping.`,
        );
        skippedCount += 1;
        continue;
      }

      let anySuccess = false;

      // --- Send FCM (Android) ---
      if (fcmTokens.length > 0 && firebaseMessaging) {
        const messages: admin.messaging.Message[] = fcmTokens.map((token) => ({
          token: token,
          data: {
            task_id: task.id,
            title: task.title,
            due_at: task.due_at ?? "",
            nag_count: String(nagNumber),
          },
          notification: {
            title: task.title,
            body: `This task is overdue. Nag #${nagNumber}`,
          },
          android: {
            priority: "high",
            notification: {
              sound: "default",
              click_action: "OPEN_ACTIVITY_1",
            },
          },
        }));

        const batchResponse = await firebaseMessaging.sendEach(messages, {
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

        if (successTokens.length > 0) {
          anySuccess = true;
        }
      }

      // --- Send Web Push ---
      if (pushSubscriptions.length > 0) {
        const webPayload = {
          title: task.title,
          body: `This task is overdue. Nag #${nagNumber}`,
          tag: `task-${task.id}`,
          task_id: task.id,
          due_at: task.due_at ?? "",
          nag_count: String(nagNumber),
          icon: "/pwa-192x192.png",
        };

        const webPushPromises = pushSubscriptions.map((sub) =>
          sendWebPush(sub, webPayload),
        );

        const results = await Promise.allSettled(webPushPromises);
        const webSuccesses = results.filter(
          (r) => r.status === "fulfilled" && r.value,
        );

        if (webSuccesses.length > 0) {
          anySuccess = true;
        }
      }

      // --- Update task state ---
      if (anySuccess) {
        notifiedCount += 1;
        await supabase
          .from("tasks")
          .update({
            last_notified_at: nowIso,
            notification_count: nagNumber,
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
