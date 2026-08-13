import { useEffect, useState } from "react";
import { getDeviceId } from "../lib/deviceId";
import { formatGroupLabel, getCurrentIdentityId, getMyGroups } from "../lib/groups";
import { supabase } from "../lib/supabaseClient";

interface Task {
  id: string;
  owner_identity_id: string | null;
  group_id: string | null;
  title: string;
  notes: string | null;
  due_at: string;
  nag_interval_minutes: number;
  status: "pending" | "done";
  completed_at: string | null;
}

interface OverviewTask extends Task {
  listName: string;
}

interface AllTasksOverviewProps {
  refreshKey: number;
  onTaskCompleted: () => void;
}

export function AllTasksOverview({
  refreshKey,
  onTaskCompleted,
}: AllTasksOverviewProps) {
  const [tasks, setTasks] = useState<OverviewTask[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    let isCurrent = true;
    let subscription: any = null;

    async function fetchOverviewTasks() {
      setIsLoading(true);
      setErrorMessage(null);

      try {
        const identityId = await getCurrentIdentityId();
        const groups = await getMyGroups();
        const groupIds = groups.map((group) => group.id);
        const groupNameById = new Map(
          groups.map((group) => [group.id, formatGroupLabel(group)]),
        );

        const { data: personalTasks, error: personalError } = await supabase
          .from("tasks")
          .select(
            "id,owner_identity_id,group_id,title,notes,due_at,nag_interval_minutes,status,completed_at",
          )
          .eq("owner_identity_id", identityId)
          .eq("status", "pending");

        if (personalError) {
          throw personalError;
        }

        let groupTasks: Task[] = [];

        if (groupIds.length > 0) {
          const { data, error } = await supabase
            .from("tasks")
            .select(
              "id,owner_identity_id,group_id,title,notes,due_at,nag_interval_minutes,status,completed_at",
            )
            .in("group_id", groupIds)
            .eq("status", "pending");

          if (error) {
            throw error;
          }

          groupTasks = (data ?? []) as Task[];
        }

        const combinedTasks = [
          ...((personalTasks ?? []) as Task[]).map((task) => ({
            ...task,
            listName: "Personal",
          })),
          ...groupTasks.map((task) => ({
            ...task,
            listName: task.group_id
              ? groupNameById.get(task.group_id) ?? "Group"
              : "Personal",
          })),
        ].sort(
          (firstTask, secondTask) =>
            new Date(firstTask.due_at).getTime() -
            new Date(secondTask.due_at).getTime(),
        );

        if (isCurrent) {
          setTasks(combinedTasks);
        }
      } catch (error) {
        if (isCurrent) {
          setErrorMessage(
            error instanceof Error
              ? error.message
              : "Failed to load all tasks.",
          );
        }
      } finally {
        if (isCurrent) {
          setIsLoading(false);
        }
      }
    }

    // Initial fetch
    fetchOverviewTasks();

    // Setup realtime subscription
    async function setupSubscription() {
      try {
        const deviceId = await getDeviceId();
        const groups = await getMyGroups();
        const groupIds = groups.map((group) => group.id);

        const channel = supabase
          .channel('all-tasks-changes')
          .on(
            'postgres_changes',
            { event: '*', schema: 'public', table: 'tasks' },
            (payload) => {
              if (!isCurrent) return;
              // Refetch all tasks to keep the list in sync
              fetchOverviewTasks();
            }
          )
          .subscribe();

        subscription = channel;
      } catch (error) {
        console.error('Error setting up realtime subscription for all tasks:', error);
      }
    }

    setupSubscription();

    return () => {
      isCurrent = false;
      if (subscription) {
        supabase.removeChannel(subscription);
      }
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
    onTaskCompleted();
  }

  return (
    <aside className="section overview-panel">
      <div className="section-header">
        <h2 className="section-title">All tasks (every group)</h2>
      </div>

      {isLoading ? <p className="muted-text">Loading all tasks...</p> : null}

      {errorMessage ? (
        <p className="error-text">{errorMessage}</p>
      ) : null}

      {!isLoading && tasks.length === 0 ? (
        <p className="muted-text">No pending tasks anywhere</p>
      ) : null}

      <ul className="task-list">
        {tasks.map((task) => (
          <li className="task-card compact-task-card" key={task.id}>
            <div className="task-content">
              <span className="task-scope-label">{task.listName}</span>
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
    </aside>
  );
}