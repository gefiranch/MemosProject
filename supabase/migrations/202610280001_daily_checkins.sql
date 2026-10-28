-- Apply only after confirming this table does not already exist in the shared database.
create table public.daily_checkins (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  checkin_date date not null,
  status text not null check (status in ('Sesuai Rencana', 'Hampir Melebihi Anggaran', 'Melebihi Anggaran')),
  reflection text not null check (length(trim(reflection)) between 1 and 2000),
  plan text not null check (length(trim(plan)) between 1 and 2000),
  created_at timestamptz not null default now(),
  unique (user_id, checkin_date)
);
alter table public.daily_checkins enable row level security;
create policy checkins_select_own on public.daily_checkins for select to authenticated using (auth.uid() = user_id);
create policy checkins_insert_own on public.daily_checkins for insert to authenticated with check (auth.uid() = user_id);
create policy checkins_update_own on public.daily_checkins for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy checkins_delete_own on public.daily_checkins for delete to authenticated using (auth.uid() = user_id);
