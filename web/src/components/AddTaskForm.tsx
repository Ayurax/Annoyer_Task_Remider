import { FormEvent, useState } from "react";
import { getDeviceId } from "../lib/deviceId";
import { getActiveGroupId } from "../lib/groups";
import { supabase } from "../lib/supabaseClient";

interface AddTaskFormProps {
  onTaskAdded: () => void;
}

export function AddTaskForm({ onTaskAdded }: AddTaskFormProps) {
  const [title, setTitle] = useState("");
  const [notes, setNotes] = useState("");
  const [dueAt, setDueAt] = useState("");
  const [nagIntervalMinutes, setNagIntervalMinutes] = useState(30);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setErrorMessage(null);
    setIsSubmitting(true);

    try {
      const activeGroupId = getActiveGroupId();
      const deviceId = activeGroupId ? null : await getDeviceId();
      const { error } = await supabase.from("tasks").insert({
        owner_device_id: deviceId,
        group_id: activeGroupId,
        title: title.trim(),
        notes: notes.trim() || null,
        due_at: new Date(dueAt).toISOString(),
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
          required
          type="datetime-local"
          value={dueAt}
          onChange={(event) => setDueAt(event.target.value)}
        />
        <span className="helper-text">Use your local date and time.</span>
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

      <button disabled={isSubmitting} type="submit">
        {isSubmitting ? "Adding..." : "Add task"}
      </button>
    </form>
  );
}
