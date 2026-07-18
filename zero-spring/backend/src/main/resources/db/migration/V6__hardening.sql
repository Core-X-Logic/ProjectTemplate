-- F5-B production readiness: PROD-R10 (soft-delete vs. unique), PROD-R11 (PG version guard),
-- PROD-R14 (login index).
--
-- V1/V2 are deliberately NOT edited: Flyway records their checksums, and changing an applied
-- migration breaks every existing installation on the next boot.

-- ---------------------------------------------------------------------------
-- PROD-R11: PostgreSQL 15+ guard.
--
-- V1 and V2 use `unique nulls not distinct`, which is PG15 syntax. On PG14 they fail with a bare
-- parser error that says nothing about the actual requirement. This block states the requirement
-- once, in a message an operator can act on. It is also why the guard is repeated as a Flyway
-- BEFORE_MIGRATE callback (PostgresVersionGuard): the callback fires ahead of V1, so a fresh
-- install on PG14 is told what is wrong instead of being handed a syntax error.
-- ---------------------------------------------------------------------------
do $$
begin
  if current_setting('server_version_num')::int < 150000 then
    raise exception
      'PostgreSQL 15 or newer is required (the schema uses UNIQUE ... NULLS NOT DISTINCT); this server reports %',
      current_setting('server_version');
  end if;
end $$;

-- ---------------------------------------------------------------------------
-- PROD-R10: make the username uniqueness rule agree with soft delete.
--
-- V1 declared `unique nulls not distinct (tenant_id, username)` over ALL rows, but V2 later added
-- soft delete (`deleted`) and User carries @SQLRestriction("deleted = false"). The application
-- therefore cannot see a soft-deleted row, yet the constraint still counts it: deleting the host
-- admin and restarting made the seeder try to insert `admin` again and the insert died on a
-- constraint pointing at a row nothing can query. Recreating that user was impossible through the
-- API, and with seeding enabled the application would not boot at all.
--
-- The partial unique index restricts the rule to live rows, matching what the application means by
-- "taken". NULLS NOT DISTINCT is kept so host-scope users (tenant_id is null) still collide with
-- each other exactly as before.
--
-- Data safety: the old constraint covered a superset of these rows, so any set of rows that
-- satisfied it also satisfies the narrower one. The index build cannot fail on existing data.
--
-- Email is intentionally left alone: no unique constraint has ever existed on users.email (V1/V2),
-- and adding one here would be a behaviour change, not a hardening — an installation with duplicate
-- addresses would fail to migrate.
-- ---------------------------------------------------------------------------
alter table users drop constraint if exists uq_users_tenant_username;

create unique index if not exists uq_users_tenant_username_live
  on users (tenant_id, username)
  nulls not distinct
  where deleted = false;

-- ---------------------------------------------------------------------------
-- PROD-R14: login lookups are case-insensitive
-- (findByTenantIdAndUsernameIgnoreCase -> `where tenant_id = ? and lower(username) = lower(?)`),
-- which no index could serve: the unique index above is on the raw column, so every login scanned
-- the tenant's users. This functional index makes it a lookup.
--
-- The composite covers `tenant_id is null` (host scope) as well, since NULL is an ordinary index key.
-- ---------------------------------------------------------------------------
create index if not exists ix_users_tenant_lower_username
  on users (tenant_id, lower(username));

-- ---------------------------------------------------------------------------
-- PROD-R15c: make the shedlock columns match what ShedLock's server-time SQL writes.
--
-- The lock provider now uses usingDbTime(), so lock timestamps come from the database clock instead
-- of from whichever node happens to take the lock — a node with a fast clock can otherwise consider
-- a held lock expired and run the job concurrently, which is the entire point of having the lock.
--
-- The catch: on PostgreSQL, ShedLock writes `timezone('utc', CURRENT_TIMESTAMP)`, which yields a
-- TIMESTAMP WITHOUT TIME ZONE carrying the UTC wall clock. V5 declared these columns timestamptz
-- (following the project convention), and storing a tz-less value into timestamptz makes PostgreSQL
-- interpret it in the *session* time zone — that is, in the JVM time zone of whichever node wrote
-- it. Two nodes in different time zones then write instants that differ by their offset, and the
-- mutual exclusion silently stops working. Measured locally: an Europe/Istanbul JVM stored every
-- lock three hours in the past.
--
-- These two columns are therefore a deliberate, documented exception to the project's timestamptz
-- rule: they hold UTC wall-clock values, exactly as ShedLock's contract specifies, and no longer
-- depend on any node's time zone. The USING clause converts the existing rows to the same UTC wall
-- clock they already represented, so no lock changes meaning across the migration.
-- ---------------------------------------------------------------------------
-- Guarded on the current type rather than run unconditionally. `x at time zone 'utc'` is its own
-- inverse: applied to a timestamptz it yields a tz-less UTC wall clock (what we want), but applied
-- to a column that is *already* tz-less it yields a timestamptz and shifts the value back. Running
-- this twice would therefore corrupt the timestamps, so it must convert only what needs converting.
do $$
begin
  if exists (
    select 1 from information_schema.columns
    where table_name = 'shedlock'
      and column_name = 'lock_until'
      and data_type = 'timestamp with time zone'
  ) then
    alter table shedlock
      alter column lock_until type timestamp using (lock_until at time zone 'utc'),
      alter column locked_at  type timestamp using (locked_at  at time zone 'utc');
  end if;
end $$;
