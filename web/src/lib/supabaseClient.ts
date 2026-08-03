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
export const supabase = createClient(supabaseUrl, supabaseAnonKey);
