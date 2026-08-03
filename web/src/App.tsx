import { useState } from "react";
import { AddTaskForm } from "./components/AddTaskForm";
import { GroupJoin } from "./components/GroupJoin";
import { TaskList } from "./components/TaskList";
import { getActiveGroupId } from "./lib/groups";

export function App() {
  const [activeGroupId, setActiveGroupIdState] = useState<string | null>(() =>
    getActiveGroupId(),
  );
  const [refreshKey, setRefreshKey] = useState(0);

  return (
    <main className="app-shell">
      <header className="app-header">
        <h1 className="app-title">Task Reminder</h1>
        <p className="app-subtitle">
          Add tasks, see what is pending, and mark them done.
        </p>
      </header>

      <GroupJoin
        activeGroupId={activeGroupId}
        onActiveGroupChange={(groupId) => {
          setActiveGroupIdState(groupId);
          setRefreshKey((key) => key + 1);
        }}
      />
      <AddTaskForm onTaskAdded={() => setRefreshKey((key) => key + 1)} />
      <TaskList activeGroupId={activeGroupId} refreshKey={refreshKey} />
    </main>
  );
}
