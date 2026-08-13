-- Store web push subscriptions (VAPID) so the Edge Function can
-- send web push alongside FCM.  Each identity can have multiple
-- subscriptions (one per browser / device).

create table if not exists public.push_subscriptions (
  id uuid primary key default gen_random_uuid(),
  identity_id uuid references public.identities(id) on delete cascade,
  endpoint text not null unique,
  p256dh text not null,
  auth text not null,
  created_at timestamptz not null default now()
);

create index if not exists push_subscriptions_identity_id_idx
  on public.push_subscriptions (identity_id);

create index if not exists push_subscriptions_endpoint_idx
  on public.push_subscriptions (endpoint);

-- RLS: identities can manage their own subscriptions
alter table public.push_subscriptions enable row level security;

create policy "push_subscriptions_select_own"
  on public.push_subscriptions
  for select
  using (
    identity_id = public.current_identity_id()
    or identity_id is null
  );

create policy "push_subscriptions_insert_own"
  on public.push_subscriptions
  for insert
  with check (identity_id = public.current_identity_id() or identity_id is null);

create policy "push_subscriptions_update_own"
  on public.push_subscriptions
  for update
  using (identity_id = public.current_identity_id() or identity_id is null)
  with check (identity_id = public.current_identity_id() or identity_id is null);

create policy "push_subscriptions_delete_own"
  on public.push_subscriptions
  for delete
  using (identity_id = public.current_identity_id() or identity_id is null);

grant select, insert, update, delete on
  public.push_subscriptions to anon, authenticated;

-- Include push_subscriptions in realtime publication
alter publication supabase_realtime add table public.push_subscriptions;
