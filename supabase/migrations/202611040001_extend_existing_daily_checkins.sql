-- For the existing shared schema or a fresh database after the create-table
-- migration. Coordinate with the database owner before applying to shared DBs.
BEGIN;

ALTER TABLE public.daily_checkins
  ADD COLUMN IF NOT EXISTS content text,
  ADD COLUMN IF NOT EXISTS status text,
  ADD COLUMN IF NOT EXISTS reflection text,
  ADD COLUMN IF NOT EXISTS plan text;

-- A fresh database may have rows from the original Journal schema.
UPDATE public.daily_checkins SET content = reflection WHERE content IS NULL;

ALTER TABLE public.daily_checkins
  ALTER COLUMN content SET NOT NULL,
  ADD CONSTRAINT daily_checkins_journal_content_check
    CHECK (length(trim(content)) > 0),
  ADD CONSTRAINT daily_checkins_journal_status_check
    CHECK (status IS NULL OR status IN ('Sesuai Rencana', 'Hampir Melebihi Anggaran', 'Melebihi Anggaran')),
  ADD CONSTRAINT daily_checkins_journal_reflection_check
    CHECK (reflection IS NULL OR length(trim(reflection)) BETWEEN 1 AND 2000),
  ADD CONSTRAINT daily_checkins_journal_plan_check
    CHECK (plan IS NULL OR length(trim(plan)) BETWEEN 1 AND 2000);

COMMIT;
