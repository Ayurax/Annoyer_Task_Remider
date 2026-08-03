-- Initial schema for the task reminder app.
-- Supabase/Postgres compatible migration.

create extension if not exists pgcrypto;

-- Anonymous device identities used instead of auth/login accounts.
create table public.devices (
  id uuid primary key default gen_random_uuid(),
  created_at timestamptz not null default now()
);

-- Small shared task groups discoverable through short human-typeable join codes.
create table public.groups (
  id uuid primary key default gen_random_uuid(),
  join_code text not null unique,
  created_at timestamptz not null default now()
);

-- Membership join table connecting anonymous devices to shared task groups.
create table public.group_members (
  group_id uuid not null references public.groups(id) on delete cascade,
  device_id uuid not null references public.devices(id) on delete cascade,
  joined_at timestamptz not null default now(),
  primary key (group_id, device_id)
);

-- Personal or group-owned tasks that drive reminders and completion state.
create table public.tasks (
  id uuid primary key default gen_random_uuid(),
  owner_device_id uuid references public.devices(id),
  group_id uuid references public.groups(id),
  title text not null,
  notes text,
  due_at timestamptz not null,
  nag_interval_minutes integer not null default 30,
  status text not null default 'pending',
  quiet_hours_start time,
  quiet_hours_end time,
  created_at timestamptz not null default now(),
  completed_at timestamptz,
  constraint tasks_status_check check (status in ('pending', 'done')),
  constraint tasks_owner_or_group_required check (
    owner_device_id is not null
    or group_id is not null
  )
);

-- Per-device progress counters for lightweight achievement and streak tracking.
create table public.achievements (
  device_id uuid primary key references public.devices(id) on delete cascade,
  tasks_completed_on_time integer not null default 0,
  current_streak integer not null default 0,
  longest_streak integer not null default 0,
  updated_at timestamptz not null default now()
);

create index tasks_group_id_idx on public.tasks(group_id);
create index tasks_owner_device_id_idx on public.tasks(owner_device_id);

alter table public.devices enable row level security;
alter table public.groups enable row level security;
alter table public.group_members enable row level security;
alter table public.tasks enable row level security;
alter table public.achievements enable row level security;

-- TODO: tighten RLS once device-id verification is added.
create policy "allow all devices access"
  on public.devices
  for all
  using (true)
  with check (true);

-- TODO: tighten RLS once device-id verification is added.
create policy "allow all groups access"
  on public.groups
  for all
  using (true)
  with check (true);

-- TODO: tighten RLS once device-id verification is added.
create policy "allow all group_members access"
  on public.group_members
  for all
  using (true)
  with check (true);

-- TODO: tighten RLS once device-id verification is added.
create policy "allow all tasks access"
  on public.tasks
  for all
  using (true)
  with check (true);

-- TODO: tighten RLS once device-id verification is added.
create policy "allow all achievements access"
  on public.achievements
  for all
  using (true)
  with check (true);

-- Example manual test data only:
-- insert into public.devices (id) values ('00000000-0000-0000-0000-000000000001');
-- insert into public.tasks (
--   owner_device_id,
--   title,
--   notes,
--   due_at
-- ) values (
--   '00000000-0000-0000-0000-000000000001',
--   'Test reminder',
--   'Manual migration smoke test task',
--   now() + interval '1 hour'
-- );
