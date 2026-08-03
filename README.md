# Task Reminder App

Personal task/reminder app with an Android client, lightweight laptop web client, and shared Supabase backend.

## Goals

- Nag the user with escalating notifications until tasks are marked done.
- Support personal tasks and small shared group task lists via join codes.
- Avoid accounts: each client generates and stores an anonymous device UUID.
- Use Supabase for Postgres, realtime updates, and backend sync.
- Use Firebase Cloud Messaging for Android push notifications.

## Project Structure

- `android/`: Native Android app in Kotlin and Jetpack Compose.
- `web/`: Lightweight React + Vite laptop client.
- `backend/supabase/`: Supabase config and Postgres migrations.

## Next Steps

1. Fill in Supabase project credentials in client configuration.
2. Add Firebase project files for Android push notifications.
3. Implement Room entities, DAOs, and offline sync.
4. Implement notification escalation and quiet-hours behavior.
5. Add group join and realtime task updates.
