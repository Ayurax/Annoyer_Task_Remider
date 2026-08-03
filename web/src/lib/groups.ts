import { getDeviceId } from "./deviceId";
import { supabase } from "./supabaseClient";

const ACTIVE_GROUP_ID_STORAGE_KEY = "active_group_id";
const JOIN_CODE_CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const JOIN_CODE_LENGTH = 6;

export interface Group {
  id: string;
  join_code: string;
  name: string | null;
  created_by_device_id: string | null;
}

interface GroupMemberRow {
  groups: Group | Group[] | null;
}

export function formatGroupLabel(group: Pick<Group, "name" | "join_code">): string {
  return group.name ? `${group.name} (${group.join_code})` : group.join_code;
}

function generateJoinCode(): string {
  return Array.from({ length: JOIN_CODE_LENGTH }, () => {
    const index = Math.floor(Math.random() * JOIN_CODE_CHARACTERS.length);
    return JOIN_CODE_CHARACTERS[index];
  }).join("");
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

  for (let attempt = 0; attempt < 3; attempt += 1) {
    const joinCode = generateJoinCode();
    const { data, error } = await supabase
      .from("groups")
      .insert({
        created_by_device_id: deviceId,
        join_code: joinCode,
        name: trimmedName || null,
      })
      .select("id,join_code,name,created_by_device_id")
      .single();

    if (error) {
      if (error.code === "23505" && attempt < 2) {
        continue;
      }

      throw new Error(`Failed to create group: ${error.message}`);
    }

    const group = data as Group;
    const { error: membershipError } = await supabase
      .from("group_members")
      .upsert(
        { group_id: group.id, device_id: deviceId },
        { onConflict: "group_id,device_id" },
      );

    if (membershipError) {
      throw new Error(`Failed to join new group: ${membershipError.message}`);
    }

    return group;
  }

  throw new Error("Failed to generate a unique join code. Try again.");
}

export async function joinGroup(joinCode: string): Promise<{ id: string } | null> {
  const normalizedJoinCode = joinCode.trim().toUpperCase();

  if (!normalizedJoinCode) {
    return null;
  }

  const { data: group, error } = await supabase
    .from("groups")
    .select("id")
    .ilike("join_code", normalizedJoinCode)
    .maybeSingle();

  if (error) {
    throw new Error(`Failed to look up group: ${error.message}`);
  }

  if (!group) {
    return null;
  }

  const deviceId = await getDeviceId();
  const { error: membershipError } = await supabase
    .from("group_members")
    .upsert(
      { group_id: group.id, device_id: deviceId },
      { onConflict: "group_id,device_id" },
    );

  if (membershipError) {
    throw new Error(`Failed to join group: ${membershipError.message}`);
  }

  return { id: group.id as string };
}

export async function getMyGroups(): Promise<Group[]> {
  const deviceId = await getDeviceId();
  const { data, error } = await supabase
    .from("group_members")
    .select("groups(id,join_code,name,created_by_device_id)")
    .eq("device_id", deviceId);

  if (error) {
    throw new Error(`Failed to load groups: ${error.message}`);
  }

  return ((data ?? []) as unknown as GroupMemberRow[])
    .map((row) => (Array.isArray(row.groups) ? row.groups[0] : row.groups))
    .filter((group): group is Group => Boolean(group));
}

export async function leaveGroup(groupId: string): Promise<void> {
  const deviceId = await getDeviceId();
  const { error } = await supabase
    .from("group_members")
    .delete()
    .eq("group_id", groupId)
    .eq("device_id", deviceId);

  if (error) {
    throw new Error(`Failed to leave group: ${error.message}`);
  }

  const { count, error: countError } = await supabase
    .from("group_members")
    .select("group_id", { count: "exact", head: true })
    .eq("group_id", groupId);

  if (countError) {
    throw new Error(`Failed to check group membership: ${countError.message}`);
  }

  if ((count ?? 0) === 0) {
    await deleteGroup(groupId, { skipOwnershipCheck: true });
  }
}

async function deleteGroupRecord(groupId: string): Promise<void> {
  const { error } = await supabase.from("groups").delete().eq("id", groupId);

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
    .select("created_by_device_id")
    .eq("id", groupId)
    .maybeSingle();

  if (lookupError) {
    throw new Error(`Failed to check group ownership: ${lookupError.message}`);
  }

  if (!group) {
    return;
  }

  if (group.created_by_device_id !== deviceId) {
    throw new Error("Only the group creator can delete this group.");
  }

  await deleteGroupRecord(groupId);
}
