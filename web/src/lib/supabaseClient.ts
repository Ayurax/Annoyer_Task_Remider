import { createClient } from "@supabase/supabase-js";

const supabaseUrl = import.meta.env.VITE_SUPABASE_URL;
const supabaseAnonKey = import.meta.env.VITE_SUPABASE_ANON_KEY;

if (!supabaseUrl) {
  throw new Error("Missing required environment variable: VITE_SUPABASE_URL");
}

if (!supabaseAnonKey) {
  throw new Error("Missing required environment variable: VITE_SUPABASE_ANON_KEY");
}

// Shared Supabase client used by all components to read/write tasks, groups, and achievements.
// The fetch interceptor injects the x-device-id header so that RLS policies
// (current_device_id(), current_identity_id()) can resolve the calling device.
export const supabase = createClient(supabaseUrl, supabaseAnonKey, {
  global: {
    fetch: (input: RequestInfo | URL, init?: RequestInit) => {
      const headers = new Headers(init?.headers);
      const deviceId = localStorage.getItem("device_id");
      if (deviceId) {
        headers.set("x-device-id", deviceId);
      }
      return fetch(input, { ...init, headers });
    },
  },
});
