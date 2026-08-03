-- Track the anonymous device that created each group.
alter table public.groups
  add column if not exists created_by_device_id uuid references public.devices(id);

-- Deleting a group cascades to its members and tasks; only the creator can trigger
-- that deletion in the app layer until RLS is tightened.
do $$
declare
  constraint_name text;
begin
  select tc.constraint_name
  into constraint_name
  from information_schema.table_constraints tc
  join information_schema.key_column_usage kcu
    on tc.constraint_schema = kcu.constraint_schema
    and tc.constraint_name = kcu.constraint_name
  where tc.constraint_schema = 'public'
    and tc.table_name = 'tasks'
    and tc.constraint_type = 'FOREIGN KEY'
    and kcu.column_name = 'group_id'
  limit 1;

  if constraint_name is not null then
    execute format('alter table public.tasks drop constraint %I', constraint_name);
  end if;

  alter table public.tasks
    add constraint tasks_group_id_fkey
    foreign key (group_id)
    references public.groups(id)
    on delete cascade;
end $$;

do $$
declare
  constraint_name text;
  delete_rule text;
begin
  select tc.constraint_name, rc.delete_rule
  into constraint_name, delete_rule
  from information_schema.table_constraints tc
  join information_schema.key_column_usage kcu
    on tc.constraint_schema = kcu.constraint_schema
    and tc.constraint_name = kcu.constraint_name
  join information_schema.referential_constraints rc
    on tc.constraint_schema = rc.constraint_schema
    and tc.constraint_name = rc.constraint_name
  where tc.constraint_schema = 'public'
    and tc.table_name = 'group_members'
    and tc.constraint_type = 'FOREIGN KEY'
    and kcu.column_name = 'group_id'
  limit 1;

  if constraint_name is not null and delete_rule <> 'CASCADE' then
    execute format('alter table public.group_members drop constraint %I', constraint_name);
    constraint_name := null;
  end if;

  if constraint_name is null then
    alter table public.group_members
      add constraint group_members_group_id_fkey
      foreign key (group_id)
      references public.groups(id)
      on delete cascade;
  end if;
end $$;

do $$
declare
  constraint_name text;
  delete_rule text;
begin
  select tc.constraint_name, rc.delete_rule
  into constraint_name, delete_rule
  from information_schema.table_constraints tc
  join information_schema.key_column_usage kcu
    on tc.constraint_schema = kcu.constraint_schema
    and tc.constraint_name = kcu.constraint_name
  join information_schema.referential_constraints rc
    on tc.constraint_schema = rc.constraint_schema
    and tc.constraint_name = rc.constraint_name
  where tc.constraint_schema = 'public'
    and tc.table_name = 'group_members'
    and tc.constraint_type = 'FOREIGN KEY'
    and kcu.column_name = 'device_id'
  limit 1;

  if constraint_name is not null and delete_rule <> 'CASCADE' then
    execute format('alter table public.group_members drop constraint %I', constraint_name);
    constraint_name := null;
  end if;

  if constraint_name is null then
    alter table public.group_members
      add constraint group_members_device_id_fkey
      foreign key (device_id)
      references public.devices(id)
      on delete cascade;
  end if;
end $$;
