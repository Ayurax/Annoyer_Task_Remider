import { useState } from "react";
import { AddTaskForm } from "./components/AddTaskForm";
import { AllTasksOverview } from "./components/AllTasksOverview";
import { GroupJoin } from "./components/GroupJoin";
import { TaskList } from "./components/TaskList";
import {
  getActiveGroupId,
  setActiveGroupId as persistActiveGroupId,
} from "./lib/groups";

export function App() {
  const [activeGroupId, setActiveGroupIdState] = useState<string | null>(() =>
    getActiveGroupId(),
  );
  const [refreshKey, setRefreshKey] = useState(0);
  const requestRefresh = () => setRefreshKey((key) => key + 1);
  const handleActiveGroupChange = (groupId: string | null) => {
    persistActiveGroupId(groupId);
    setActiveGroupIdState(groupId);
    requestRefresh();
  };

  return (
    <main className="app-shell">
      <header className="app-header">
        <h1 className="app-title">Task Reminder</h1>
        <p className="app-subtitle">
          Add tasks, see what is pending, and mark them done.
        </p>
      </header>

      <div className="app-layout">
        <div className="primary-column">
          <GroupJoin
            activeGroupId={activeGroupId}
            onActiveGroupChange={handleActiveGroupChange}
            onGroupsChanged={requestRefresh}
            refreshKey={refreshKey}
          />
          <AddTaskForm onTaskAdded={requestRefresh} />
          <TaskList
            activeGroupId={activeGroupId}
            onActiveGroupMissing={() => handleActiveGroupChange(null)}
            onTaskCompleted={requestRefresh}
            refreshKey={refreshKey}
          />
        </div>

        <AllTasksOverview
          onTaskCompleted={requestRefresh}
          refreshKey={refreshKey}
        />
      </div>
    </main>
  );
}
