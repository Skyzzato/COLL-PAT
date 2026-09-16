-- Eseguire nel SQL Editor come postgres. Nessun dato di prova viene conservato.
BEGIN;
DO $check$
DECLARE item record;
BEGIN
    IF (SELECT version_num FROM collettori.alembic_version) <> '0001' THEN
        RAISE EXCEPTION 'Versione Alembic inattesa';
    END IF;
    FOR item IN SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname='collettori'
    LOOP
        IF NOT (SELECT relrowsecurity FROM pg_catalog.pg_class WHERE oid=format('collettori.%I', item.tablename)::regclass) THEN
            RAISE EXCEPTION 'RLS assente: %', item.tablename;
        END IF;
        IF has_table_privilege('anon', format('collettori.%I', item.tablename), 'SELECT,INSERT,UPDATE,DELETE')
           OR has_table_privilege('authenticated', format('collettori.%I', item.tablename), 'SELECT,INSERT,UPDATE,DELETE')
           OR has_table_privilege('service_role', format('collettori.%I', item.tablename), 'SELECT,INSERT,UPDATE,DELETE') THEN
            RAISE EXCEPTION 'Privilegi client inattesi: %', item.tablename;
        END IF;
    END LOOP;
END $check$;
SET LOCAL ROLE collettori_backend;
INSERT INTO collettori.areas(id,name,synthetic) VALUES ('__supabase_migration_check__','Test transazionale',true);
UPDATE collettori.areas SET name='Test RLS backend' WHERE id='__supabase_migration_check__';
SELECT id,name FROM collettori.areas WHERE id='__supabase_migration_check__';
DELETE FROM collettori.areas WHERE id='__supabase_migration_check__';
SELECT version_num FROM collettori.alembic_version;
ROLLBACK;
