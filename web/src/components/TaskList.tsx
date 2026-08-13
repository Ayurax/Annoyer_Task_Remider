import { useEffect, useState } from "react";
import { getDeviceId } from "../lib/deviceId";
import { getMyGroups, getCurrentIdentityId } from "../lib/groups";
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
  activeGroupId: string | null;
  onActiveGroupMissing: () => void;
  onTaskCompleted: () => void;
  refreshKey: number;
}

export function TaskList({
  activeGroupId,
  onActiveGroupMissing,
  onTaskCompleted,
  refreshKey,
}: TaskListProps) {
  const [tasks, setTasks] = useState<Task[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    let isCurrent = true;
    let subscription: any = null;

    async function fetchTasks() {
      setIsLoading(true);
      setErrorMessage(null);

      try {
        let query = supabase
          .from("tasks")
          .select(
            "id,title,notes,due_at,nag_interval_minutes,status,completed_at",
          )
          .eq("status", "pending")
          .order("due_at", { ascending: true });

        if (activeGroupId) {
          const groups = await getMyGroups();

          if (!groups.some((group) => group.id === activeGroupId)) {
            if (isCurrent) {
              setTasks([]);
              onActiveGroupMissing();
            }

            return;
          }

          query = query.eq("group_id", activeGroupId);
        } else {
          const identityId = await getCurrentIdentityId();
          query = query.eq("owner_identity_id", identityId);
        }

        const { data, error } = await query;

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

    // Initial fetch
    fetchTasks();

    // Setup realtime subscription
    async function setupSubscription() {
      try {
        const deviceId = await getDeviceId();
        const channel = supabase
          .channel('tasks-changes')
          .on(
            'postgres_changes',
            { event: '*', schema: 'public', table: 'tasks' },
            (payload) => {
              if (!isCurrent) return;
              // Refetch the tasks to keep the list in sync
              fetchTasks();
            }
          )
          .subscribe();

        subscription = channel;
      } catch (error) {
        console.error('Error setting up realtime subscription:', error);
      }
    }

    setupSubscription();

    return () => {
      isCurrent = false;
      if (subscription) {
        supabase.removeChannel(subscription);
      }
    };
  }, [activeGroupId, onActiveGroupMissing, refreshKey]);

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
    onTaskCompleted();
  }

  if (isLoading) {
    return <p className="muted-text">Loading tasks...</p>;
  }

  return (
    <section className="section">
      <div className="section-header">
        <h2 className="section-title">
          {activeGroupId ? "Group pending tasks" : "Personal pending tasks"}
        </h2>
      </div>

      {errorMessage ? (
        <p className="error-text">{errorMessage}</p>
      ) : null}

      {tasks.length === 0 ? <p className="muted-text">No pending tasks</p> : null}

      <ul className="task-list">
        {tasks.map((task) => (
          <li className="task-card" key={task.id}>
            <div className="task-content">
              <h3 className="task-title">{task.title}</h3>
              <p className="task-meta">
                Due {new Date(task.due_at).toLocaleString()}
              </p>
              {task.notes ? <p className="task-notes">{task.notes}</p> : null}
            </div>
            <div className="task-actions">
              <button type="button" onClick={() => markDone(task.id)}>
                Mark done
              </button>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}