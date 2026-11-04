create or replace function public.seed_default_categories()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  begin
    insert into public.categories (user_id, name, type) values
      (new.id,'Gaji','income'), (new.id,'Bonus','income'),
      (new.id,'Makanan','expense'), (new.id,'Transportasi','expense'),
      (new.id,'Hiburan','expense'), (new.id,'Belanja','expense')
    on conflict do nothing;
  exception when others then
    raise warning 'seed categories gagal: %', sqlerrm;
  end;
  return new;
end; $$;

revoke execute on function public.seed_default_categories() from public, anon, authenticated;

drop trigger if exists on_auth_user_seed_categories on auth.users;
create trigger on_auth_user_seed_categories
after insert on auth.users
for each row execute function public.seed_default_categories();

insert into public.categories (user_id, name, type)
select u.id, c.name, c.type
from auth.users u
cross join (values
  ('Gaji','income'), ('Bonus','income'),
  ('Makanan','expense'), ('Transportasi','expense'),
  ('Hiburan','expense'), ('Belanja','expense')
) as c(name, type)
on conflict do nothing;