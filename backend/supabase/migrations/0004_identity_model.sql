-- Replace device-only/auth.user ownership with anonymous identities shared by linked devices.

create table if not exists public.identities (
  id uuid primary key default gen_random_uuid(),
  link_code text not null unique,
  created_at timestamptz not null default now(),
  constraint identities_link_code_format check (link_code ~ '^[A-Z0-9]{6}$')
);

create or replace function public.generate_identity_link_code()
returns text
language plpgsql
volatile
as $$
declare
  characters constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  generated_code text;
begin
  loop
    generated_code := '';
    for index in 1..6 loop
      generated_code := generated_code || substr(
        characters,
        floor(random() * length(characters) + 1)::integer,
        1
      );
    end loop;

    if not exists (
      select 1 from public.identities where link_code = generated_code
    ) then
      return generated_code;
    end if;
  end loop;
end;
$$;

alter table public.devices
  add column if not exists identity_id uuid references public.identities(id) on delete cascade;

with identity_seed as (
  select
    d.id as device_id,
    gen_random_uuid() as identity_id,
    public.generate_identity_link_code() as link_code
  from public.devices d
  where d.identity_id is null
),
inserted_identities as (
  insert into public.identities (id, link_code)
  select identity_id, link_code
  from identity_seed
  returning id
)
update public.devices d
set identity_id = identity_seed.identity_id
from identity_seed
where d.id = identity_seed.device_id;

alter table public.devices
  alter column identity_id set not null;

alter table public.groups
  drop column if exists owner_id,
  add column if not exists created_by_identity_id uuid references public.identities(id) on delete set null;

update public.groups g
set created_by_identity_id = d.identity_id
from public.group_members gm
join public.devices d on d.id = gm.device_id
where gm.group_id = g.id
  and g.created_by_identity_id is null;

alter table public.group_members
  drop constraint if exists group_members_pkey,
  drop constraint if exists group_members_device_id_fkey,
  add column if not exists identity_id uuid;

update public.group_members gm
set identity_id = d.identity_id
from public.devices d
where gm.device_id = d.id
  and gm.identity_id is null;

alter table public.group_members
  alter column identity_id set not null,
  drop column if exists device_id,
  add constraint group_members_identity_id_fkey
    foreign key (identity_id) references public.identities(id) on delete cascade,
  add constraint group_members_pkey primary key (group_id, identity_id);

alter table public.tasks
  drop constraint if exists tasks_owner_device_id_fkey,
  drop constraint if exists tasks_owner_or_group_required;

alter table public.tasks
  rename column owner_device_id to owner_identity_id;

update public.tasks t
set owner_identity_id = d.identity_id
from public.devices d
where t.owner_identity_id = d.id;

alter table public.tasks
  add constraint tasks_owner_identity_id_fkey
    foreign key (owner_identity_id) references public.identities(id) on delete cascade,
  drop constraint if exists tasks_group_id_fkey,
  add constraint tasks_group_id_fkey
    foreign key (group_id) references public.groups(id) on delete cascade,
  add constraint tasks_owner_or_group_required check (
    (owner_identity_id is not null and group_id is null)
    or (owner_identity_id is null and group_id is not null)
  );

alter index if exists public.tasks_owner_device_id_idx
  rename to tasks_owner_identity_id_idx;

alter table public.achievements
  drop constraint if exists achievements_device_id_fkey;

alter table public.achievements
  rename column device_id to identity_id;

update public.achievements a
set identity_id = d.identity_id
from public.devices d
where a.identity_id = d.id;

alter table public.achievements
  add constraint achievements_identity_id_fkey
    foreign key (identity_id) references public.identities(id) on delete cascade;

create or replace function public.current_device_id()
returns uuid
language plpgsql
stable
as $$
declare
  raw_headers text;
  header_value text;
begin
  raw_headers := coalesce(nullif(current_setting('request.headers', true), ''), '{}');
  header_value := raw_headers::jsonb ->> 'x-device-id';
  return nullif(header_value, '')::uuid;
exception
  when others then
    return null;
end;
$$;

create or replace function public.current_identity_id()
returns uuid
language sql
stable
security definer
set search_path = public
as $$
  select d.identity_id
  from public.devices d
  where d.id = public.current_device_id()
$$;

create or replace function public.is_group_member(target_group_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1
    from public.group_members gm
    where gm.group_id = target_group_id
      and gm.identity_id = public.current_identity_id()
  )
$$;

create or replace function public.is_group_creator(target_group_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1
    from public.groups g
    where g.id = target_group_id
      and g.created_by_identity_id = public.current_identity_id()
  )
$$;

create or replace function public.create_identity_for_device(
  p_device_id uuid,
  p_link_code text default null
)
returns table (device_id uuid, identity_id uuid, link_code text)
language plpgsql
security definer
set search_path = public
as $$
declare
  requested_code text := nullif(upper(trim(p_link_code)), '');
  new_identity_id uuid;
  new_link_code text;
begin
  new_link_code := coalesce(requested_code, public.generate_identity_link_code());

  insert into public.identities (link_code)
  values (new_link_code)
  returning id into new_identity_id;

  insert into public.devices (id, identity_id)
  values (p_device_id, new_identity_id)
  on conflict (id) do update
  set identity_id = excluded.identity_id;

  return query
  select p_device_id, new_identity_id, new_link_code;
end;
$$;

create or replace function public.link_device_to_identity(
  p_device_id uuid,
  p_link_code text
)
returns table (device_id uuid, identity_id uuid, link_code text)
language plpgsql
security definer
set search_path = public
as $$
declare
  matched_identity public.identities%rowtype;
begin
  select *
  into matched_identity
  from public.identities i
  where i.link_code = upper(trim(p_link_code));

  if matched_identity.id is null then
    raise exception 'Identity link code not found';
  end if;

  insert into public.devices (id, identity_id)
  values (p_device_id, matched_identity.id)
  on conflict (id) do update
  set identity_id = excluded.identity_id;

  return query
  select p_device_id, matched_identity.id, matched_identity.link_code;
end;
$$;

create or replace function public.identity_for_device(p_device_id uuid)
returns uuid
language sql
stable
security definer
set search_path = public
as $$
  select d.identity_id
  from public.devices d
  where d.id = p_device_id
$$;

create or replace function public.create_group_for_device(
  p_device_id uuid,
  p_name text
)
returns table (
  id uuid,
  join_code citext,
  name text,
  created_by_identity_id uuid
)
language plpgsql
security definer
set search_path = public
as $$
declare
  caller_identity_id uuid;
  new_group_id uuid;
  new_join_code citext;
begin
  caller_identity_id := public.identity_for_device(p_device_id);

  if caller_identity_id is null then
    raise exception 'Device identity not found';
  end if;

  loop
    new_join_code := public.generate_identity_link_code()::citext;
    begin
      insert into public.groups (join_code, name, created_by_identity_id)
      values (new_join_code, nullif(trim(p_name), ''), caller_identity_id)
      returning groups.id into new_group_id;
      exit;
    exception
      when unique_violation then
        null;
    end;
  end loop;

  insert into public.group_members (group_id, identity_id)
  values (new_group_id, caller_identity_id)
  on conflict (group_id, identity_id) do nothing;

  return query
  select g.id, g.join_code, g.name, g.created_by_identity_id
  from public.groups g
  where g.id = new_group_id;
end;
$$;

create or replace function public.join_group_for_device(
  p_device_id uuid,
  p_join_code text
)
returns table (
  id uuid,
  join_code citext,
  name text,
  created_by_identity_id uuid
)
language plpgsql
security definer
set search_path = public
as $$
declare
  caller_identity_id uuid;
  matched_group_id uuid;
begin
  caller_identity_id := public.identity_for_device(p_device_id);

  if caller_identity_id is null then
    raise exception 'Device identity not found';
  end if;

  select g.id
  into matched_group_id
  from public.groups g
  where g.join_code = upper(trim(p_join_code))::citext;

  if matched_group_id is null then
    return;
  end if;

  insert into public.group_members (group_id, identity_id)
  values (matched_group_id, caller_identity_id)
  on conflict (group_id, identity_id) do nothing;

  return query
  select g.id, g.join_code, g.name, g.created_by_identity_id
  from public.groups g
  where g.id = matched_group_id;
end;
$$;

create or replace function public.get_groups_for_device(p_device_id uuid)
returns table (
  id uuid,
  join_code citext,
  name text,
  created_by_identity_id uuid
)
language sql
stable
security definer
set search_path = public
as $$
  select g.id, g.join_code, g.name, g.created_by_identity_id
  from public.groups g
  join public.group_members gm on gm.group_id = g.id
  where gm.identity_id = public.identity_for_device(p_device_id)
  order by g.created_at asc
$$;

create or replace function public.leave_group_for_device(
  p_device_id uuid,
  p_group_id uuid
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  caller_identity_id uuid;
  remaining_members integer;
begin
  caller_identity_id := public.identity_for_device(p_device_id);

  if caller_identity_id is null then
    raise exception 'Device identity not found';
  end if;

  perform 1
  from public.groups g
  where g.id = p_group_id
  for update;

  delete from public.group_members gm
  where gm.group_id = p_group_id
    and gm.identity_id = caller_identity_id;

  select count(*)
  into remaining_members
  from public.group_members gm
  where gm.group_id = p_group_id;

  if remaining_members = 0 then
    delete from public.groups g
    where g.id = p_group_id;
  end if;
end;
$$;

create or replace function public.delete_group_for_device(
  p_device_id uuid,
  p_group_id uuid
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  caller_identity_id uuid;
begin
  caller_identity_id := public.identity_for_device(p_device_id);

  if caller_identity_id is null then
    raise exception 'Device identity not found';
  end if;

  delete from public.groups g
  where g.id = p_group_id
    and g.created_by_identity_id = caller_identity_id;

  if not found then
    raise exception 'Only the group creator can delete this group.';
  end if;
end;
$$;

drop policy if exists "allow all devices access" on public.devices;
drop policy if exists "allow all groups access" on public.groups;
drop policy if exists "allow all group_members access" on public.group_members;
drop policy if exists "allow all tasks access" on public.tasks;
drop policy if exists "allow all achievements access" on public.achievements;

alter table public.identities enable row level security;

create policy "identities_select_own"
  on public.identities
  for select
  using (id = public.current_identity_id());

create policy "devices_select_same_identity"
  on public.devices
  for select
  using (identity_id = public.current_identity_id());

create policy "devices_update_current_device"
  on public.devices
  for update
  using (id = public.current_device_id())
  with check (id = public.current_device_id());

create policy "groups_select_members"
  on public.groups
  for select
  using (public.is_group_member(id));

create policy "groups_insert_creator"
  on public.groups
  for insert
  with check (created_by_identity_id = public.current_identity_id());

create policy "groups_update_creator"
  on public.groups
  for update
  using (created_by_identity_id = public.current_identity_id())
  with check (created_by_identity_id = public.current_identity_id());

create policy "groups_delete_creator"
  on public.groups
  for delete
  using (created_by_identity_id = public.current_identity_id());

create policy "group_members_select_members"
  on public.group_members
  for select
  using (public.is_group_member(group_id) or identity_id = public.current_identity_id());

create policy "group_members_insert_self"
  on public.group_members
  for insert
  with check (identity_id = public.current_identity_id());

create policy "group_members_delete_self_or_creator"
  on public.group_members
  for delete
  using (
    identity_id = public.current_identity_id()
    or public.is_group_creator(group_id)
  );

create policy "tasks_select_owned_or_group"
  on public.tasks
  for select
  using (
    owner_identity_id = public.current_identity_id()
    or public.is_group_member(group_id)
  );

create policy "tasks_insert_owned_or_group"
  on public.tasks
  for insert
  with check (
    (owner_identity_id = public.current_identity_id() and group_id is null)
    or (owner_identity_id is null and public.is_group_member(group_id))
  );

create policy "tasks_update_owned_or_group"
  on public.tasks
  for update
  using (
    owner_identity_id = public.current_identity_id()
    or public.is_group_member(group_id)
  )
  with check (
    owner_identity_id = public.current_identity_id()
    or public.is_group_member(group_id)
  );

create policy "tasks_delete_owned_or_group"
  on public.tasks
  for delete
  using (
    owner_identity_id = public.current_identity_id()
    or public.is_group_member(group_id)
  );

create policy "achievements_select_own"
  on public.achievements
  for select
  using (identity_id = public.current_identity_id());

create policy "achievements_insert_own"
  on public.achievements
  for insert
  with check (identity_id = public.current_identity_id());

create policy "achievements_update_own"
  on public.achievements
  for update
  using (identity_id = public.current_identity_id())
  with check (identity_id = public.current_identity_id());

grant usage on schema public to anon, authenticated;
grant select, insert, update, delete on
  public.identities,
  public.devices,
  public.groups,
  public.group_members,
  public.tasks,
  public.achievements
to anon, authenticated;
grant execute on function public.create_identity_for_device(uuid, text) to anon, authenticated;
grant execute on function public.link_device_to_identity(uuid, text) to anon, authenticated;
grant execute on function public.create_group_for_device(uuid, text) to anon, authenticated;
grant execute on function public.join_group_for_device(uuid, text) to anon, authenticated;
grant execute on function public.get_groups_for_device(uuid) to anon, authenticated;
grant execute on function public.leave_group_for_device(uuid, uuid) to anon, authenticated;
grant execute on function public.delete_group_for_device(uuid, uuid) to anon, authenticated;

do $$
declare
  table_name text;
begin
  foreach table_name in array array[
    'identities',
    'devices',
    'groups',
    'group_members',
    'tasks',
    'achievements'
  ] loop
    if not exists (
      select 1
      from pg_publication_tables
      where pubname = 'supabase_realtime'
        and schemaname = 'public'
        and tablename = table_name
    ) then
      execute format('alter publication supabase_realtime add table public.%I', table_name);
    end if;
  end loop;
end;
$$;
