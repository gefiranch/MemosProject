begin;

create table public.goals (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    name text not null,
    target_amount numeric(18, 2) not null,
    saved_amount numeric(18, 2) not null default 0,
    description text,
    image_path text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint goals_name_valid check (name = btrim(name) and char_length(name) between 1 and 100),
    constraint goals_target_positive check (target_amount > 0 and target_amount < 'Infinity'::numeric),
    constraint goals_saved_nonnegative check (saved_amount >= 0 and saved_amount < 'Infinity'::numeric),
    constraint goals_description_length check (description is null or char_length(description) <= 2000),
    -- Store a private object key, never a public/signed URL. One goal cannot reference another goal's image.
    constraint goals_image_path_valid check (
        image_path is null or (
            image_path like user_id::text || '/' || id::text || '/%'
            and image_path ~ ('^' || user_id::text || '/' || id::text || '/[0-9a-f-]{36}\.(jpg|png|webp)$')
        )
    )
);

create index goals_user_created_at_idx on public.goals(user_id, created_at desc, id);

create function public.set_goal_timestamps()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    if TG_OP = 'UPDATE' then
        if new.id is distinct from old.id or new.user_id is distinct from old.user_id then
            raise exception 'Goal ID and owner cannot be changed' using errcode = '23514';
        end if;
        new.created_at := old.created_at;
    else
        new.created_at := now();
    end if;
    new.updated_at := now();
    return new;
end;
$$;

revoke all on function public.set_goal_timestamps() from public;
create trigger goals_set_timestamps before insert or update on public.goals
for each row execute function public.set_goal_timestamps();

alter table public.goals enable row level security;
alter table public.goals force row level security;
revoke all on public.goals from anon, authenticated;
grant select, insert, update, delete on public.goals to authenticated;

create policy goals_select_own on public.goals for select to authenticated
using ((select auth.uid()) = user_id);

create policy goals_insert_own on public.goals for insert to authenticated
with check ((select auth.uid()) = user_id);

create policy goals_update_own on public.goals for update to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

create policy goals_delete_own on public.goals for delete to authenticated
using ((select auth.uid()) = user_id);

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('goal-images', 'goal-images', false, 5242880, array['image/jpeg', 'image/png', 'image/webp'])
on conflict (id) do update set
    public = false,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

-- Files use <auth.uid()>/<goal UUID>/<image UUID>.<extension>.
-- INSERT permits upload before the goal row is created. Replacements use new object keys.
create policy goal_images_select_own on storage.objects for select to authenticated
using (bucket_id = 'goal-images' and (storage.foldername(name))[1] = (select auth.uid())::text);

create policy goal_images_insert_own on storage.objects for insert to authenticated
with check (
    bucket_id = 'goal-images'
    and (storage.foldername(name))[1] = (select auth.uid())::text
    and name ~ ('^' || (select auth.uid())::text || '/[0-9a-f-]{36}/[0-9a-f-]{36}\.(jpg|png|webp)$')
);

create policy goal_images_update_own on storage.objects for update to authenticated
using (bucket_id = 'goal-images' and (storage.foldername(name))[1] = (select auth.uid())::text)
with check (
    bucket_id = 'goal-images'
    and (storage.foldername(name))[1] = (select auth.uid())::text
    and name ~ ('^' || (select auth.uid())::text || '/[0-9a-f-]{36}/[0-9a-f-]{36}\.(jpg|png|webp)$')
);

create policy goal_images_delete_own on storage.objects for delete to authenticated
using (bucket_id = 'goal-images' and (storage.foldername(name))[1] = (select auth.uid())::text);

commit;
