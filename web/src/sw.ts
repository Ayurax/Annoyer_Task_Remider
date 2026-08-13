// @ts-nocheck
/// <reference types="vite-plugin-pwa/types" />

import { precacheAndRoute, cleanupOutdatedCaches } from "workbox-precaching";
import { registerRoute, NavigationRoute } from "workbox-routing";

// Precache all build assets — __WB_MANIFEST is injected by vite-plugin-pwa
precacheAndRoute(self.__WB_MANIFEST);
cleanupOutdatedCaches();

// SPA fallback: serve index.html for navigation requests
registerRoute(
  new NavigationRoute(({ request }) => {
    return caches.match(request).then((cached) => {
      if (cached) return cached;
      return caches.match("index.html");
    });
  }),
);

// --- Push event handler ---
self.addEventListener("push", (event) => {
  const payload = event.data?.json() ?? {};

  const options = {
    body: payload.body,
    icon: payload.icon ?? "/pwa-192x192.png",
    badge: "/pwa-192x192.png",
    tag: payload.tag ?? `task-${payload.task_id}`,
    data: {
      taskId: payload.task_id,
      url: "/",
    },
    renotify: true,
    requireInteraction: false,
  };

  event.waitUntil(self.registration.showNotification(payload.title, options));
});

// --- Notification click handler ---
self.addEventListener("notificationclick", (event) => {
  event.notification.close();

  const url = event.notification.data?.url ?? "/";

  event.waitUntil(
    clients.matchAll({ type: "window", includeUncontrolled: true }).then(
      (clientList) => {
        for (const client of clientList) {
          if (client.url === url && "focus" in client) {
            return client.focus();
          }
        }
        if (clients.openWindow) {
          return clients.openWindow(url);
        }
      },
    ),
  );
});

// --- Install ---
self.addEventListener("install", () => {
  self.skipWaiting();
});

// --- Activate ---
self.addEventListener("activate", () => {
  self.clients.claim();
});
