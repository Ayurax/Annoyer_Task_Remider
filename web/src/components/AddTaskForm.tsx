import { FormEvent, useState } from "react";
import { getDeviceId } from "../lib/deviceId";
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
      const deviceId = await getDeviceId();
      const { error } = await supabase.from("tasks").insert({
        owner_device_id: deviceId,
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
    <form onSubmit={handleSubmit} style={{ display: "grid", gap: "0.75rem" }}>
      <h2 style={{ margin: 0 }}>Add task</h2>

      <label>
        Title
        <input
          required
          type="text"
          value={title}
          onChange={(event) => setTitle(event.target.value)}
          style={{ display: "block", marginTop: "0.25rem", width: "100%" }}
        />
      </label>

      <label>
        Notes
        <textarea
          value={notes}
          onChange={(event) => setNotes(event.target.value)}
          rows={3}
          style={{ display: "block", marginTop: "0.25rem", width: "100%" }}
        />
      </label>

      <label>
        Due date and time
        <input
          required
          type="datetime-local"
          value={dueAt}
          onChange={(event) => setDueAt(event.target.value)}
          style={{ display: "block", marginTop: "0.25rem", width: "100%" }}
        />
      </label>

      <label>
        Nag interval in minutes
        <input
          min={1}
          required
          type="number"
          value={nagIntervalMinutes}
          onChange={(event) =>
            setNagIntervalMinutes(Number(event.target.value))
          }
          style={{ display: "block", marginTop: "0.25rem", width: "100%" }}
        />
      </label>

      {errorMessage ? (
        <p style={{ color: "#b00020", margin: 0 }}>{errorMessage}</p>
      ) : null}

      <button disabled={isSubmitting} type="submit">
        {isSubmitting ? "Adding..." : "Add task"}
      </button>
    </form>
  );
}
