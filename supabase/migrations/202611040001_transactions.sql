-- Migrasi referensi skema & RLS untuk tabel transactions dan categories
-- Fitur: Transaction Management
-- Penanggung Jawab: Alexandra Pritha Sari (NIM 245150401111006)

begin;

-- 1. Pastikan tabel categories ada (jika belum dibuat di Supabase)
create table if not exists public.categories (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    name text not null,
    type text not null check (type in ('pemasukan', 'pengeluaran')),
    created_at timestamptz not null default now()
);

-- RLS categories
alter table public.categories enable row level security;

do $$
begin
    if not exists (select 1 from pg_policies where tablename = 'categories' and policyname = 'categories_all_own') then
        create policy categories_all_own on public.categories for all to authenticated
        using ((select auth.uid()) = user_id)
        with check ((select auth.uid()) = user_id);
    end if;
end $$;

-- 2. Pastikan tabel transactions ada (sesuai skema wajib)
create table if not exists public.transactions (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    category_id uuid not null references public.categories(id) on delete restrict,
    type text not null check (type in ('pemasukan', 'pengeluaran')),
    amount numeric not null check (amount > 0),
    transaction_date date not null default current_date,
    description text,
    created_at timestamptz not null default now()
);

-- Index untuk performa query transaksi per user
create index if not exists transactions_user_date_idx on public.transactions(user_id, transaction_date desc, created_at desc);

-- RLS transactions
alter table public.transactions enable row level security;
alter table public.transactions force row level security;
grant select, insert, update, delete on public.transactions to authenticated;

-- Policies CRUD transactions
do $$
begin
    if not exists (select 1 from pg_policies where tablename = 'transactions' and policyname = 'transactions_select_own') then
        create policy transactions_select_own on public.transactions for select to authenticated
        using ((select auth.uid()) = user_id);
    end if;

    if not exists (select 1 from pg_policies where tablename = 'transactions' and policyname = 'transactions_insert_own') then
        create policy transactions_insert_own on public.transactions for insert to authenticated
        with check ((select auth.uid()) = user_id);
    end if;

    if not exists (select 1 from pg_policies where tablename = 'transactions' and policyname = 'transactions_update_own') then
        create policy transactions_update_own on public.transactions for update to authenticated
        using ((select auth.uid()) = user_id)
        with check ((select auth.uid()) = user_id);
    end if;

    if not exists (select 1 from pg_policies where tablename = 'transactions' and policyname = 'transactions_delete_own') then
        create policy transactions_delete_own on public.transactions for delete to authenticated
        using ((select auth.uid()) = user_id);
    end if;
end $$;

commit;
