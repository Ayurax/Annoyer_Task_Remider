/**
 * Anonymous browser identity helper.
 *
 * TODO: Generate and store a random UUID in localStorage on first load.
 * TODO: Reuse that UUID for Supabase writes and group joins.
 */
export function getOrCreateDeviceId(): string {
  throw new Error("TODO: implement localStorage-backed device UUID");
}
