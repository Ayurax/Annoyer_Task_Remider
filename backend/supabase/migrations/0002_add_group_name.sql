-- Stores an optional human-readable group name, such as "My Devices" or "CS101 Group Project".
alter table public.groups
  add column name text;
