import { supabase } from "./supabaseClient";

const DEVICE_ID_STORAGE_KEY = "device_id";

export async function getDeviceId(): Promise<string> {
  const existingDeviceId = localStorage.getItem(DEVICE_ID_STORAGE_KEY);

  if (existingDeviceId) {
    return existingDeviceId;
  }

  const deviceId = crypto.randomUUID();

  // Use the security-definer RPC instead of a direct table insert.
  // create_identity_for_device creates both the identity and device
  // atomically, bypassing RLS. The anon-key INSERT would fail because
  // there is no INSERT policy on the devices table.
  const { error } = await supabase.rpc("create_identity_for_device", {
    p_device_id: deviceId,
  });

  if (error) {
    throw new Error(`Failed to register anonymous device: ${error.message}`);
  }

  localStorage.setItem(DEVICE_ID_STORAGE_KEY, deviceId);
  return deviceId;
}
