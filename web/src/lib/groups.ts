import { getDeviceId } from "./deviceId";
import { supabase } from "./supabaseClient";

const ACTIVE_GROUP_ID_STORAGE_KEY = "active_group_id";
export interface Group {
  id: string;
  join_code: string;
  name: string | null;
  created_by_identity_id: string | null;
}

export function formatGroupLabel(group: Pick<Group, "name" | "join_code">): string {
  return group.name ? `${group.name} (${group.join_code})` : group.join_code;
}

export function getActiveGroupId(): string | null {
  return localStorage.getItem(ACTIVE_GROUP_ID_STORAGE_KEY);
}

export function setActiveGroupId(groupId: string | null): void {
  if (groupId) {
    localStorage.setItem(ACTIVE_GROUP_ID_STORAGE_KEY, groupId);
    return;
  }

  localStorage.removeItem(ACTIVE_GROUP_ID_STORAGE_KEY);
}

export async function createGroup(name?: string): Promise<Group> {
  const deviceId = await getDeviceId();
  const trimmedName = name?.trim();

  const { data, error } = await supabase
    .rpc("create_group_for_device", {
      p_device_id: deviceId,
      p_name: trimmedName || null,
    })
    .single();

  if (error) {
    throw new Error(`Failed to create group: ${error.message}`);
  }

  return data as Group;
}

export async function joinGroup(joinCode: string): Promise<{ id: string } | null> {
  const normalizedJoinCode = joinCode.trim().toUpperCase();

  if (!normalizedJoinCode) {
    return null;
  }

  const deviceId = await getDeviceId();
  const { data: group, error } = await supabase
    .rpc("join_group_for_device", {
      p_device_id: deviceId,
      p_join_code: normalizedJoinCode,
    })
    .maybeSingle();

  if (error) {
    throw new Error(`Failed to join group: ${error.message}`);
  }

  if (!group) {
    return null;
  }

  return { id: (group as Group).id };
}

export async function getMyGroups(): Promise<Group[]> {
  const deviceId = await getDeviceId();
  const { data, error } = await supabase
    .rpc("get_groups_for_device", { p_device_id: deviceId });

  if (error) {
    throw new Error(`Failed to load groups: ${error.message}`);
  }

  return (data ?? []) as Group[];
}

export async function getCurrentIdentityId(): Promise<string> {
  const deviceId = await getDeviceId();
  const { data, error } = await supabase
    .rpc("identity_for_device", { p_device_id: deviceId })
    .single();

  if (error) {
    throw new Error(`Failed to load identity: ${error.message}`);
  }

  if (!data) {
    throw new Error("Device identity not found.");
  }

  return data as string;
}

export async function leaveGroup(groupId: string): Promise<void> {
  const deviceId = await getDeviceId();
  const { error } = await supabase
    .rpc("leave_group_for_device", {
      p_device_id: deviceId,
      p_group_id: groupId,
    });

  if (error) {
    throw new Error(`Failed to leave group: ${error.message}`);
  }
}

async function deleteGroupRecord(groupId: string): Promise<void> {
  const deviceId = await getDeviceId();
  const { error } = await supabase
    .rpc("delete_group_for_device", {
      p_device_id: deviceId,
      p_group_id: groupId,
    });

  if (error) {
    throw new Error(`Failed to delete group: ${error.message}`);
  }
}

export async function deleteGroup(
  groupId: string,
  options?: { skipOwnershipCheck?: boolean },
): Promise<void> {
  if (options?.skipOwnershipCheck) {
    await deleteGroupRecord(groupId);
    return;
  }

  const deviceId = await getDeviceId();
  const { data: group, error: lookupError } = await supabase
    .from("groups")
    .select("created_by_identity_id")
    .eq("id", groupId)
    .maybeSingle();

  if (lookupError) {
    throw new Error(`Failed to check group ownership: ${lookupError.message}`);
  }

  if (!group) {
    return;
  }

  await deleteGroupRecord(groupId);
}

export async function getOrCreateIdentityLinkCode(): Promise<string> {
  const deviceId = await getDeviceId();
  const { data, error } = await supabase
    .rpc("create_identity_for_device", { p_device_id: deviceId });

  if (error) {
    throw new Error(`Failed to get/create identity: ${error.message}`);
  }

  if (!data || data.length === 0) {
    throw new Error("No identity data returned");
  }

  // The RPC returns an array of objects, we want the first one's link_code
  return (data as Array<{link_code: string}>)[0].link_code;
}

export async function linkDeviceToIdentity(linkCode: string): Promise<string> {
  const deviceId = await getDeviceId();
  const { data, error } = await supabase
    .rpc("link_device_to_identity", {
      p_device_id: deviceId,
      p_link_code: linkCode.toUpperCase().trim()
    });

  if (error) {
    throw new Error(`Failed to link device to identity: ${error.message}`);
  }

  if (!data || data.length === 0) {
    throw new Error("No identity data returned from linking");
  }

  // The RPC returns an array of objects, we want the first one's link_code
  return (data as Array<{link_code: string}>)[0].link_code;
}
