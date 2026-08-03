import { useEffect, useState } from "react";
import { getDeviceId } from "../lib/deviceId";
import { supabase } from "../lib/supabaseClient";

interface Task {
  id: string;
  title: string;
  notes: string | null;
  due_at: string;
  nag_interval_minutes: number;
  status: "pending" | "done";
  completed_at: string | null;
}

interface TaskListProps {
  refreshKey: number;
}

export function TaskList({ refreshKey }: TaskListProps) {
  const [tasks, setTasks] = useState<Task[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    let isCurrent = true;

    async function fetchTasks() {
      setIsLoading(true);
      setErrorMessage(null);

      try {
        const deviceId = await getDeviceId();
        const { data, error } = await supabase
          .from("tasks")
          .select(
            "id,title,notes,due_at,nag_interval_minutes,status,completed_at",
          )
          .eq("owner_device_id", deviceId)
          .eq("status", "pending")
          .order("due_at", { ascending: true });

        if (error) {
          throw error;
        }

        if (isCurrent) {
          setTasks((data ?? []) as Task[]);
        }
      } catch (error) {
        if (isCurrent) {
          setErrorMessage(
            error instanceof Error ? error.message : "Failed to load tasks.",
          );
        }
      } finally {
        if (isCurrent) {
          setIsLoading(false);
        }
      }
    }

    fetchTasks();

    return () => {
      isCurrent = false;
    };
  }, [refreshKey]);

  async function markDone(taskId: string) {
    setErrorMessage(null);

    const { error } = await supabase
      .from("tasks")
      .update({
        status: "done",
        completed_at: new Date().toISOString(),
      })
      .eq("id", taskId);

    if (error) {
      setErrorMessage(error.message);
      return;
    }

    setTasks((currentTasks) =>
      currentTasks.filter((task) => task.id !== taskId),
    );
  }

  if (isLoading) {
    return <p>Loading tasks...</p>;
  }

  return (
    <section style={{ display: "grid", gap: "0.75rem" }}>
      <h2 style={{ margin: 0 }}>Pending tasks</h2>

      {errorMessage ? (
        <p style={{ color: "#b00020", margin: 0 }}>{errorMessage}</p>
      ) : null}

      {tasks.length === 0 ? <p>No pending tasks</p> : null}

      <ul style={{ display: "grid", gap: "0.75rem", listStyle: "none", padding: 0 }}>
        {tasks.map((task) => (
          <li
            key={task.id}
            style={{ border: "1px solid #ddd", padding: "0.75rem" }}
          >
            <h3 style={{ margin: "0 0 0.25rem" }}>{task.title}</h3>
            <p style={{ margin: "0 0 0.5rem" }}>
              Due {new Date(task.due_at).toLocaleString()}
            </p>
            {task.notes ? <p style={{ marginTop: 0 }}>{task.notes}</p> : null}
            <button type="button" onClick={() => markDone(task.id)}>
              Mark done
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
