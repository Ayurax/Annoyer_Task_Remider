-- Initial schema for the task reminder app.
-- Devices are anonymous UUIDs generated locally by Android DataStore or web localStorage.

create type public.task_status as enum ('pending', 'done');

create table public.devices (
  id uuid primary key,
  created_at timestamptz not null default now()
);

create table public.groups (
  id uuid primary key default gen_random_uuid(),
  join_code text not null unique,
  created_at timestamptz not null default now(),
  constraint groups_join_code_not_blank check (length(trim(join_code)) > 0)
);

create table public.group_members (
  group_id uuid not null references public.groups(id) on delete cascade,
  device_id uuid not null references public.devices(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (group_id, device_id)
);

create table public.tasks (
  id uuid primary key default gen_random_uuid(),
  owner_device_id uuid references public.devices(id) on delete cascade,
  group_id uuid references public.groups(id) on delete cascade,
  title text not null,
  notes text,
  due_at timestamptz not null,
  nag_interval_minutes integer not null default 15,
  status public.task_status not null default 'pending',
  quiet_hours_start time,
  quiet_hours_end time,
  created_at timestamptz not null default now(),
  completed_at timestamptz,
  constraint tasks_owner_or_group check (
    (owner_device_id is not null and group_id is null)
    or (owner_device_id is null and group_id is not null)
  ),
  constraint tasks_nag_interval_positive check (nag_interval_minutes > 0),
  constraint tasks_completed_when_done check (
    (status = 'done' and completed_at is not null)
    or (status = 'pending' and completed_at is null)
  )
);

create table public.achievements (
  device_id uuid primary key references public.devices(id) on delete cascade,
  tasks_completed_on_time integer not null default 0,
  current_streak integer not null default 0,
  longest_streak integer not null default 0,
  constraint achievements_counts_non_negative check (
    tasks_completed_on_time >= 0
    and current_streak >= 0
    and longest_streak >= 0
  )
);

create index tasks_owner_device_id_idx on public.tasks(owner_device_id);
create index tasks_group_id_idx on public.tasks(group_id);
create index tasks_due_at_idx on public.tasks(due_at);
create index tasks_status_idx on public.tasks(status);
create index groups_join_code_idx on public.groups(join_code);

-- TODO: Add RLS policies that allow auth-less anonymous device IDs without exposing unrelated devices/groups.
-- TODO: Add functions or edge functions for join-code creation, FCM fanout, and notification scheduling.
