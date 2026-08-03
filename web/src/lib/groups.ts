import { getDeviceId } from "./deviceId";
import { supabase } from "./supabaseClient";

const ACTIVE_GROUP_ID_STORAGE_KEY = "active_group_id";
const JOIN_CODE_CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const JOIN_CODE_LENGTH = 6;

export interface Group {
  id: string;
  join_code: string;
  name: string | null;
}

interface GroupMemberRow {
  groups: Group | Group[] | null;
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

  for (let attempt = 0; attempt < 5; attempt += 1) {
    const joinCode = generateJoinCode();
    const { data, error } = await supabase
      .from("groups")
      .insert({ join_code: joinCode, name: trimmedName || null })
      .select("id,join_code,name")
      .single();

    if (error) {
      if (error.code === "23505") {
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
    .select("groups(id,join_code,name)")
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
}
