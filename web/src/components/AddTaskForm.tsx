import { FormEvent, useEffect, useRef, useState } from "react";
import { getDeviceId } from "../lib/deviceId";
import { formatGroupLabel, Group } from "../lib/groups";
import { supabase } from "../lib/supabaseClient";

interface AddTaskFormProps {
  activeGroupId: string | null;
  joinedGroups: Group[];
  onTaskAdded: () => void;
}

type DueAtParseResult =
  | {
      isoString: string;
    }
  | {
      errorMessage: string;
    };

function parseDueAt(value: string): DueAtParseResult {
  const match = value.match(
    /^(\d{4,})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?$/,
  );

  if (!match) {
    return { errorMessage: "Enter a valid date and time." };
  }

  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6] ?? "0");
  const millisecond = Number((match[7] ?? "0").padEnd(3, "0"));

  const currentYear = new Date().getFullYear();
  const maxYear = currentYear + 10;

  if (year < currentYear || year > maxYear) {
    return {
      errorMessage: `Choose a year between ${currentYear} and ${maxYear}.`,
    };
  }

  const parsedDate = new Date(
    year,
    month - 1,
    day,
    hour,
    minute,
    second,
    millisecond,
  );

  if (
    Number.isNaN(parsedDate.getTime()) ||
    parsedDate.getFullYear() !== year ||
    parsedDate.getMonth() + 1 !== month ||
    parsedDate.getDate() !== day ||
    parsedDate.getHours() !== hour ||
    parsedDate.getMinutes() !== minute ||
    parsedDate.getSeconds() !== second
  ) {
    return { errorMessage: "Enter a valid date and time." };
  }

  return { isoString: parsedDate.toISOString() };
}

export function AddTaskForm({
  activeGroupId,
  joinedGroups,
  onTaskAdded,
}: AddTaskFormProps) {
  const [title, setTitle] = useState("");
  const [notes, setNotes] = useState("");
  const [dueAt, setDueAt] = useState("");
  const [nagIntervalMinutes, setNagIntervalMinutes] = useState(30);
  const [assignedGroupId, setAssignedGroupId] = useState<string | null>(
    activeGroupId,
  );
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [dueAtErrorMessage, setDueAtErrorMessage] = useState<string | null>(
    null,
  );
  const [isSubmitting, setIsSubmitting] = useState(false);
  const dueAtInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    setAssignedGroupId(activeGroupId);
  }, [activeGroupId]);

  useEffect(() => {
    if (
      assignedGroupId &&
      !joinedGroups.some((group) => group.id === assignedGroupId)
    ) {
      setAssignedGroupId(activeGroupId);
    }
  }, [activeGroupId, assignedGroupId, joinedGroups]);

  function getAssignmentLabel(groupId: string | null): string {
    if (!groupId) {
      return "Personal (just me)";
    }

    const group = joinedGroups.find((candidate) => candidate.id === groupId);

    return group ? formatGroupLabel(group) : "Personal (just me)";
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setErrorMessage(null);
    setSuccessMessage(null);
    setDueAtErrorMessage(null);

    const parsedDueAt = parseDueAt(dueAt);

    if ("errorMessage" in parsedDueAt) {
      setDueAtErrorMessage(parsedDueAt.errorMessage);
      dueAtInputRef.current?.focus();
      return;
    }

    setIsSubmitting(true);

    try {
      const deviceId = assignedGroupId ? null : await getDeviceId();
      const { error } = await supabase.from("tasks").insert({
        owner_identity_id: deviceId,
        group_id: assignedGroupId,
        title: title.trim(),
        notes: notes.trim() || null,
        due_at: parsedDueAt.isoString,
        nag_interval_minutes: nagIntervalMinutes,
        status: "pending",
      });

      if (error) {
        throw error;
      }

      setTitle("");
      setNotes("");
      setDueAt("");
      setNagIntervalMinutes(30);
      setAssignedGroupId(activeGroupId);
      if (assignedGroupId !== activeGroupId) {
        setSuccessMessage(`Added to ${getAssignmentLabel(assignedGroupId)}`);
      }
      onTaskAdded();
    } catch (error) {
      setErrorMessage(
        error instanceof Error ? error.message : "Failed to add task.",
      );
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <form className="section form-grid" onSubmit={handleSubmit}>
      <div className="section-header">
        <h2 className="section-title">Add task</h2>
      </div>

      <label className="field">
        <span className="field-label">Title</span>
        <input
          required
          type="text"
          value={title}
          onChange={(event) => setTitle(event.target.value)}
        />
      </label>

      <label className="field">
        <span className="field-label">Notes</span>
        <textarea
          value={notes}
          onChange={(event) => setNotes(event.target.value)}
          rows={3}
        />
      </label>

      <label className="field">
        <span className="field-label">Due date and time</span>
        <input
          ref={dueAtInputRef}
          required
          type="datetime-local"
          value={dueAt}
          onChange={(event) => {
            setDueAt(event.target.value);
            if (dueAtErrorMessage) {
              setDueAtErrorMessage(null);
            }
          }}
        />
        {dueAtErrorMessage ? (
          <p className="error-text">{dueAtErrorMessage}</p>
        ) : null}
        <span className="helper-text">Use your local date and time.</span>
      </label>

      <label className="field">
        <span className="field-label">Assign to</span>
        <select
          value={assignedGroupId ?? ""}
          onChange={(event) => {
            setAssignedGroupId(event.target.value || null);
            setSuccessMessage(null);
          }}
        >
          <option value="">Personal (just me)</option>
          {joinedGroups.map((group) => (
            <option key={group.id} value={group.id}>
              {formatGroupLabel(group)}
            </option>
          ))}
        </select>
      </label>

      <label className="field">
        <span className="field-label">Nag interval in minutes</span>
        <input
          min={1}
          required
          type="number"
          value={nagIntervalMinutes}
          onChange={(event) =>
            setNagIntervalMinutes(Number(event.target.value))
          }
        />
      </label>

      {errorMessage ? (
        <p className="error-text">{errorMessage}</p>
      ) : null}

      {successMessage ? <p className="muted-text">{successMessage}</p> : null}

      <button disabled={isSubmitting} type="submit">
        {isSubmitting ? "Adding..." : "Add task"}
      </button>
    </form>
  );
}
