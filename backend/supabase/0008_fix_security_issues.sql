-- Security fixes:
-- 1. Fix push_subscriptions RLS to remove "identity_id IS NULL" wildcard
-- 2. Add devices INSERT policy (for first-time device registration via x-device-id header)
-- 3. Add rate limiting table for link code brute-force protection

-- 1. Fix push_subscriptions RLS policies — remove the dangerous "or identity_id is null" clause
--    that allowed any authenticated user to read/write/delete ALL subscriptions.
drop policy if exists "push_subscriptions_select_own" on public.push_subscriptions;
drop policy if exists "push_subscriptions_insert_own" on public.push_subscriptions;
drop policy if exists "push_subscriptions_update_own" on public.push_subscriptions;
drop policy if exists "push_subscriptions_delete_own" on public.push_subscriptions;

create policy "push_subscriptions_select_own"
  on public.push_subscriptions
  for select
  using (identity_id = public.current_identity_id());

create policy "push_subscriptions_insert_own"
  on public.push_subscriptions
  for insert
  with check (identity_id = public.current_identity_id());

create policy "push_subscriptions_update_own"
  on public.push_subscriptions
  for update
  using (identity_id = public.current_identity_id())
  with check (identity_id = public.current_identity_id());

create policy "push_subscriptions_delete_own"
  on public.push_subscriptions
  for delete
  using (identity_id = public.current_identity_id());

-- 2. Add devices INSERT policy — allows inserting a device whose id matches
--    the x-device-id request header. This is needed for first-time registration
--    in deviceId.ts before an identity is established.
--    Note: identity_id will be populated later via create_identity_for_device RPC.
--    The policy restricts insert to the device whose id matches the header,
--    preventing users from registering devices on behalf of others.
create policy "devices_insert_self"
  on public.devices
  for insert
  with check (id = public.current_device_id());

-- 3. Create rate limiting table for link code attempts
create table if not exists public.link_code_attempts (
  id uuid primary key default gen_random_uuid(),
  ip_address inet not null,
  attempted_code text not null,
  success boolean not null,
  attempted_at timestamptz not null default now()
);

create index if not exists link_code_attempts_ip_idx
  on public.link_code_attempts (ip_address, attempted_at desc);

create index if not exists link_code_attempts_code_idx
  on public.link_code_attempts (attempted_code, attempted_at desc);

-- Allow the link_device_to_identity function to log attempts
-- (uses service role, so no RLS needed for inserts)
grant select, insert on public.link_code_attempts to anon, authenticated;

-- 4. Add rate limiting to link_device_to_identity function
--    Blocks brute-force attacks on the 6-character sync code.
--    Uses x-forwarded-for header for client IP (works with Supabase edge).
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
  raw_headers text;
  client_ip inet;
  recent_failures integer;
begin
  -- Extract client IP for rate limiting
  raw_headers := coalesce(nullif(current_setting('request.headers', true), ''), '{}');
  client_ip := nullif(
    split_part(
      nullif(trim(both ' ' from (raw_headers::jsonb ->> 'x-forwarded-for')), ''),
      ',', 1
    ), ''
  )::inet;

  -- Rate limit: max 10 failed attempts per IP in 5 minutes
  if client_ip is not null then
    select count(*)
    into recent_failures
    from public.link_code_attempts
    where ip_address = client_ip
      and attempted_at > now() - interval '5 minutes'
      and success = false;

    if recent_failures >= 10 then
      if client_ip is not null then
        insert into public.link_code_attempts (ip_address, attempted_code, success)
        values (client_ip, upper(trim(p_link_code)), false);
      end if;
      raise exception 'Too many failed attempts. Please try again later.';
    end if;
  end if;

  -- Look up identity by link code
  select *
  into matched_identity
  from public.identities i
  where i.link_code = upper(trim(p_link_code));

  if matched_identity.id is null then
    -- Log failed attempt
    if client_ip is not null then
      insert into public.link_code_attempts (ip_address, attempted_code, success)
      values (client_ip, upper(trim(p_link_code)), false);
    end if;

    raise exception 'Identity link code not found';
  end if;

  -- Link device to identity
  insert into public.devices (id, identity_id)
  values (p_device_id, matched_identity.id)
  on conflict (id) do update
  set identity_id = excluded.identity_id;

  -- Log successful attempt
  if client_ip is not null then
    insert into public.link_code_attempts (ip_address, attempted_code, success)
    values (client_ip, upper(trim(p_link_code)), true);
  end if;

  return query
  select p_device_id, matched_identity.id, matched_identity.link_code;
end;
$$;
