import { useState } from "react";
import { AddTaskForm } from "./components/AddTaskForm";
import { TaskList } from "./components/TaskList";

export function App() {
  const [refreshKey, setRefreshKey] = useState(0);

  return (
    <main
      style={{
        display: "grid",
        gap: "2rem",
        maxWidth: "42rem",
        margin: "2rem auto",
        padding: "0 1rem",
        fontFamily:
          'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
        fontSize: "1rem",
        lineHeight: 1.5,
      }}
    >
      <header>
        <h1 style={{ marginBottom: "0.25rem" }}>Task Reminder</h1>
        <p style={{ margin: 0 }}>Add tasks, see what is pending, and mark them done.</p>
      </header>

      <AddTaskForm onTaskAdded={() => setRefreshKey((key) => key + 1)} />
      <TaskList refreshKey={refreshKey} />
    </main>
  );
}
