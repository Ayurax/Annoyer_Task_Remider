import { AddTaskForm } from "./components/AddTaskForm";
import { GroupJoin } from "./components/GroupJoin";
import { TaskList } from "./components/TaskList";

/**
 * Web app shell.
 *
 * TODO: Wire shared state, Supabase realtime subscriptions, anonymous device identity,
 * and routes or tabs for task list, add task, and group join.
 */
export function App() {
  return (
    <main>
      {/* TODO: Replace scaffold markup with the final laptop web UI. */}
      <TaskList />
      <AddTaskForm />
      <GroupJoin />
    </main>
  );
}
