import { createClient } from "@supabase/supabase-js";

/**
 * Supabase client boundary for the web app.
 *
 * TODO: Load VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY from environment.
 * TODO: Add typed database definitions after the initial schema stabilizes.
 */
export const supabase = createClient(
  import.meta.env.VITE_SUPABASE_URL ?? "",
  import.meta.env.VITE_SUPABASE_ANON_KEY ?? "",
);
