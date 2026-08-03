import { useEffect, useState } from "react";
import {
  createGroup,
  deleteGroup,
  getMyGroups,
  Group,
  joinGroup,
  leaveGroup,
  setActiveGroupId,
} from "../lib/groups";
import { getDeviceId } from "../lib/deviceId";

interface GroupJoinProps {
  activeGroupId: string | null;
  onActiveGroupChange: (groupId: string | null) => void;
  onGroupsChanged: () => void;
  refreshKey: number;
}

export function GroupJoin({
  activeGroupId,
  onActiveGroupChange,
  onGroupsChanged,
  refreshKey,
}: GroupJoinProps) {
  const [groups, setGroups] = useState<Group[]>([]);
  const [currentDeviceId, setCurrentDeviceId] = useState<string | null>(null);
  const [newGroupName, setNewGroupName] = useState("");
  const [joinCode, setJoinCode] = useState("");
  const [latestCreatedGroup, setLatestCreatedGroup] = useState<Group | null>(
    null,
  );
  const [loadErrorMessage, setLoadErrorMessage] = useState<string | null>(null);
  const [createErrorMessage, setCreateErrorMessage] = useState<string | null>(
    null,
  );
  const [joinErrorMessage, setJoinErrorMessage] = useState<string | null>(null);
  const [copyMessage, setCopyMessage] = useState<string | null>(null);
  const [confirmDeleteGroupId, setConfirmDeleteGroupId] = useState<
    string | null
  >(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isCreating, setIsCreating] = useState(false);
  const [isJoining, setIsJoining] = useState(false);

  async function loadGroups() {
    setIsLoading(true);
    setLoadErrorMessage(null);

    try {
      const deviceId = await getDeviceId();
      const myGroups = await getMyGroups();

      setCurrentDeviceId(deviceId);
      setGroups(myGroups);

      if (
        activeGroupId &&
        !myGroups.some((group) => group.id === activeGroupId)
      ) {
        setActiveGroupId(null);
        onActiveGroupChange(null);
        setConfirmDeleteGroupId(null);
      }
    } catch (error) {
      setLoadErrorMessage(
        error instanceof Error ? error.message : "Failed to load groups.",
      );
    } finally {
      setIsLoading(false);
    }
  }

  useEffect(() => {
    loadGroups();
  }, [activeGroupId, refreshKey]);

  function updateActiveGroup(groupId: string | null) {
    setActiveGroupId(groupId);
    onActiveGroupChange(groupId);
  }

  async function handleCreateGroup() {
    setCreateErrorMessage(null);
    setCopyMessage(null);
    setIsCreating(true);

    try {
      const group = await createGroup(newGroupName);
      setLatestCreatedGroup(group);
      setNewGroupName("");
      updateActiveGroup(group.id);
      await loadGroups();
      onGroupsChanged();
    } catch (error) {
      setCreateErrorMessage(
        error instanceof Error ? error.message : "Failed to create group.",
      );
    } finally {
      setIsCreating(false);
    }
  }

  async function handleJoinGroup() {
    setJoinErrorMessage(null);
    setIsJoining(true);

    try {
      const group = await joinGroup(joinCode);

      if (!group) {
        setJoinErrorMessage("No group found for that join code.");
        return;
      }

      setJoinCode("");
      updateActiveGroup(group.id);
      await loadGroups();
      onGroupsChanged();
    } catch (error) {
      setJoinErrorMessage(
        error instanceof Error ? error.message : "Failed to join group.",
      );
    } finally {
      setIsJoining(false);
    }
  }

  async function handleLeaveGroup(groupId: string) {
    setLoadErrorMessage(null);

    try {
      await leaveGroup(groupId);

      if (activeGroupId === groupId) {
        updateActiveGroup(null);
      }

      await loadGroups();
      onGroupsChanged();
    } catch (error) {
      setLoadErrorMessage(
        error instanceof Error ? error.message : "Failed to leave group.",
      );
    }
  }

  async function handleDeleteGroup(groupId: string) {
    setLoadErrorMessage(null);

    try {
      await deleteGroup(groupId);

      if (activeGroupId === groupId) {
        updateActiveGroup(null);
      }

      if (latestCreatedGroup?.id === groupId) {
        setLatestCreatedGroup(null);
      }

      setConfirmDeleteGroupId(null);
      await loadGroups();
      onGroupsChanged();
    } catch (error) {
      setLoadErrorMessage(
        error instanceof Error ? error.message : "Failed to delete group.",
      );
    }
  }

  async function copyLatestJoinCode() {
    if (!latestCreatedGroup) {
      return;
    }

    try {
      await navigator.clipboard.writeText(latestCreatedGroup.join_code);
      setCopyMessage("Copied.");
    } catch {
      setCopyMessage("Copy failed. Select the code and copy it manually.");
    }
  }

  return (
    <section className="section scope-panel">
      <div className="section-header">
        <h2 className="section-title">Task scope</h2>
      </div>

      <label className="field">
        <span className="field-label">Active list</span>
        <select
          value={activeGroupId ?? ""}
          onChange={(event) => updateActiveGroup(event.target.value || null)}
        >
          <option value="">Personal (no group)</option>
          {groups.map((group) => (
            <option key={group.id} value={group.id}>
              {group.name ? `${group.name} (${group.join_code})` : group.join_code}
            </option>
          ))}
        </select>
      </label>

      <div className="form-grid">
        <label className="field">
          <span className="field-label">New group name</span>
          <input
            placeholder="Optional, e.g. My Devices"
            type="text"
            value={newGroupName}
            onChange={(event) => setNewGroupName(event.target.value)}
          />
        </label>
        <div>
          <button disabled={isCreating} type="button" onClick={handleCreateGroup}>
            {isCreating ? "Creating..." : "Create new group"}
          </button>
        </div>
        {createErrorMessage ? (
          <p className="error-text">{createErrorMessage}</p>
        ) : null}
      </div>

      {latestCreatedGroup ? (
        <div className="join-code-panel" aria-live="polite">
          <div>
            <p className="join-code-label">Share join code</p>
            <span className="join-code-value">
              {latestCreatedGroup.join_code}
            </span>
          </div>
          <div className="button-row">
            <button type="button" onClick={copyLatestJoinCode}>
              Copy code
            </button>
          </div>
          {copyMessage ? <p className="muted-text">{copyMessage}</p> : null}
        </div>
      ) : null}

      <div className="form-grid">
        <label className="field">
          <span className="field-label">Join existing group</span>
          <div className="row">
            <input
              aria-label="Join code"
              placeholder="Join code"
              value={joinCode}
              onChange={(event) => setJoinCode(event.target.value.toUpperCase())}
            />
            <button disabled={isJoining} type="button" onClick={handleJoinGroup}>
              {isJoining ? "Joining..." : "Join group"}
            </button>
          </div>
        </label>
        {joinErrorMessage ? (
          <p className="error-text">{joinErrorMessage}</p>
        ) : null}
      </div>

      {loadErrorMessage ? (
        <p className="error-text">{loadErrorMessage}</p>
      ) : null}

      {isLoading ? <p className="muted-text">Loading groups...</p> : null}

      {!isLoading && groups.length > 0 ? (
        <ul className="group-list">
          {groups.map((group) => (
            <li className="group-item" key={group.id}>
              <div>
                <span>
                  {group.name ? (
                    <>
                      <span className="group-name">{group.name}</span>{" "}
                      <span className="group-code">({group.join_code})</span>
                    </>
                  ) : (
                    <>
                      <span className="group-code">Join code:</span>{" "}
                      <strong>{group.join_code}</strong>
                    </>
                  )}
                </span>
                {confirmDeleteGroupId === group.id ? (
                  <p className="muted-text">Delete this group and its tasks?</p>
                ) : null}
              </div>
              <div className="group-actions">
                <button
                  className="secondary-button"
                  type="button"
                  onClick={() => handleLeaveGroup(group.id)}
                >
                  Leave
                </button>
                {currentDeviceId === group.created_by_device_id ? (
                  confirmDeleteGroupId === group.id ? (
                    <>
                      <button
                        className="danger-button"
                        type="button"
                        onClick={() => handleDeleteGroup(group.id)}
                      >
                        Confirm
                      </button>
                      <button
                        className="secondary-button"
                        type="button"
                        onClick={() => setConfirmDeleteGroupId(null)}
                      >
                        Cancel
                      </button>
                    </>
                  ) : (
                    <button
                      className="danger-button"
                      type="button"
                      onClick={() => setConfirmDeleteGroupId(group.id)}
                    >
                      Delete group
                    </button>
                  )
                ) : null}
              </div>
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  );
}
