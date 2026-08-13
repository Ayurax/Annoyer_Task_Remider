-- Drop duplicate quiet_hours columns from tasks table
-- The app uses devices.quiet_hours_start/end exclusively for quiet hours logic
-- Columns in tasks table are remnants from migration 0006 and are unused

ALTER TABLE public.tasks DROP COLUMN IF EXISTS quiet_hours_start;
ALTER TABLE public.tasks DROP COLUMN IF EXISTS quiet_hours_end;
