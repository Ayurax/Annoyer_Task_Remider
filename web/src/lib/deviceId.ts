import { supabase } from "./supabaseClient";

const DEVICE_ID_STORAGE_KEY = "device_id";

export async function getDeviceId(): Promise<string> {
  const existingDeviceId = localStorage.getItem(DEVICE_ID_STORAGE_KEY);

  if (existingDeviceId) {
    return existingDeviceId;
  }

  const deviceId = crypto.randomUUID();
  const { error } = await supabase.from("devices").insert({ id: deviceId });

  if (error) {
    throw new Error(`Failed to register anonymous device: ${error.message}`);
  }

  localStorage.setItem(DEVICE_ID_STORAGE_KEY, deviceId);
  return deviceId;
}
