import { supabase } from "./supabaseClient";
import { getCurrentIdentityId } from "./groups";

const VAPID_PUBLIC_KEY = import.meta.env.VITE_PUBLIC_VAPID_KEY ?? "";

export function isPushSupported(): boolean {
  return (
    "serviceWorker" in navigator &&
    "PushManager" in window &&
    "Notification" in window
  );
}

export async function requestNotificationPermission(): Promise<NotificationPermission> {
  const permission = await Notification.requestPermission();
  return permission;
}

export async function subscribeToPush(): Promise<PushSubscription | null> {
  if (!isPushSupported()) {
    throw new Error("Push notifications are not supported in this browser.");
  }

  const permission = await requestNotificationPermission();
  if (permission !== "granted") {
    throw new Error("Notification permission not granted.");
  }

  if (!VAPID_PUBLIC_KEY) {
    throw new Error("VAPID public key is not configured.");
  }

  const registration = await navigator.serviceWorker.ready;

  const subscription = await registration.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: urlBase64ToUint8Array(VAPID_PUBLIC_KEY),
  });

  // Save subscription to Supabase
  const identityId = await getCurrentIdentityId();

  const { error } = await supabase.from("push_subscriptions").upsert({
    identity_id: identityId,
    endpoint: subscription.endpoint,
    p256dh: arrayBufferToBase64(subscription.getKey("p256dh")),
    auth: arrayBufferToBase64(subscription.getKey("auth")),
  }, {
    onConflict: "endpoint",
  });

  if (error) {
    throw new Error(`Failed to save push subscription: ${error.message}`);
  }

  return subscription;
}

export async function unsubscribeFromPush(): Promise<void> {
  if (!isPushSupported()) return;

  const registration = await navigator.serviceWorker.ready;
  const subscription = await registration.pushManager.getSubscription();

  if (subscription) {
    await subscription.unsubscribe();
    // Remove from Supabase
    const { error } = await supabase
      .from("push_subscriptions")
      .delete()
      .eq("endpoint", subscription.endpoint);

    if (error) {
      console.error("Failed to remove push subscription from Supabase:", error.message);
    }
  }
}

export async function getPushSubscriptionStatus(): Promise<boolean> {
  if (!isPushSupported()) return false;
  const registration = await navigator.serviceWorker.ready;
  const subscription = await registration.pushManager.getSubscription();
  return subscription != null;
}

function urlBase64ToUint8Array(base64String: string): ArrayBuffer {
  const padding = "=".repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding).replace(/-/g, "+").replace(/_/g, "/");
  const rawData = atob(base64);
  const outputArray = new Uint8Array(rawData.length);
  for (let i = 0; i < rawData.length; ++i) {
    outputArray[i] = rawData.charCodeAt(i);
  }
  return outputArray.buffer;
}

function arrayBufferToBase64(buffer: ArrayBuffer | Uint8Array | null): string {
  if (!buffer) return "";
  const bytes = buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer);
  let binary = "";
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}
