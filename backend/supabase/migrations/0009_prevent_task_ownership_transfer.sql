-- Security: Prevent task ownership transfer via UPDATE

-- The tasks_update_owned_or_group policy's WITH CHECK clause allows a group
-- member to change a group task's owner_identity_id to their own identity and
-- set group_id to NULL, effectively "stealing" the task from the group.
--
-- While we also tighten the WITH CHECK clause as defense-in-depth, the primary
-- control is a trigger that prevents changing owner_identity_id or group_id
-- on UPDATE. Ownership changes should be done via dedicated RPC functions
-- with explicit auth checks.

-- 1. Tighten the UPDATE policy's WITH CHECK to match INSERT policy structure.
--    This is defense-in-depth — the trigger below is the primary control.
drop policy if exists "tasks_update_owned_or_group" on public.tasks;

create policy "tasks_update_owned_or_group"
  on public.tasks
  for update
  using (
    owner_identity_id = public.current_identity_id()
    or public.is_group_member(group_id)
  )
  with check (
    (owner_identity_id = public.current_identity_id() and group_id is null)
    or (owner_identity_id is null and public.is_group_member(group_id))
  );

-- 2. Create a trigger to prevent ownership/group changes on UPDATE.
--    This is the primary control — RLS WITH CHECK alone cannot reference OLD
--    values, so a trigger is necessary to block ownership transfers.
create or replace function public.prevent_task_ownership_change()
returns trigger
language plpgsql
as $$
begin
  if old.owner_identity_id is distinct from new.owner_identity_id
     or old.group_id is distinct from new.group_id then
    raise exception 'Cannot change task ownership or group assignment via UPDATE';
  end if;
  return new;
end;
$$;

create trigger prevent_task_ownership_change
  before update of owner_identity_id, group_id
  on public.tasks
  for each row
  execute function public.prevent_task_ownership_change();
