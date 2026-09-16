"""Generate the initial Supabase schema from the pilot ORM; review before deployment."""
from pathlib import Path
import sys
from sqlalchemy import MetaData
from sqlalchemy.dialects import postgresql
from sqlalchemy.schema import CreateTable, CreateIndex, AddConstraint

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
from app.db import Base
from app import models

OUTPUT = ROOT / "supabase/migrations/202609160001_pilot.sql"

def render():
    dialect = postgresql.dialect()
    metadata = MetaData()
    for table in Base.metadata.sorted_tables:
        table.to_metadata(metadata, schema="collettori")
    parts = ["""-- Pilota 0.1: database Supabase dietro FastAPI, NON accesso diretto mobile.
-- Eseguire una sola volta come postgres su un progetto nuovo/dedicato.
-- Nessun dato o account applicativo viene importato da questo file.
BEGIN;
-- Intenzionalmente senza IF NOT EXISTS: una seconda esecuzione deve fallire.
CREATE SCHEMA collettori;
CREATE ROLE collettori_backend NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
REVOKE ALL ON SCHEMA collettori FROM PUBLIC, anon, authenticated, service_role;
GRANT USAGE ON SCHEMA collettori TO collettori_backend;
CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA extensions;
"""]
    for table in metadata.sorted_tables:
        parts.append(str(CreateTable(table, include_foreign_key_constraints=[]).compile(dialect=dialect)).strip() + ";")
        for constraint in sorted(table.foreign_key_constraints, key=lambda item: tuple(column.name for column in item.columns)):
            parts.append(str(AddConstraint(constraint).compile(dialect=dialect)).strip() + ";")
        for index in sorted(table.indexes, key=lambda item: item.name):
            parts.append(str(CreateIndex(index).compile(dialect=dialect)) + ";")
    parts.append("""
-- Rispetta anche installazioni PostGIS preesistenti in extensions, gis o public.
DO $migration$
DECLARE gis_schema text;
BEGIN
    SELECT n.nspname INTO STRICT gis_schema
    FROM pg_catalog.pg_extension e JOIN pg_catalog.pg_namespace n ON n.oid=e.extnamespace
    WHERE e.extname='postgis';
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO collettori_backend', gis_schema);
    EXECUTE format('CREATE TABLE collettori.reference_geometries (
        dataset_id varchar(36) NOT NULL REFERENCES collettori.datasets(id),
        entity_id varchar(36) NOT NULL,
        geom %I.geometry(Geometry,4326) NOT NULL,
        PRIMARY KEY(dataset_id,entity_id))', gis_schema);
END $migration$;
CREATE INDEX reference_geom_gist ON collettori.reference_geometries USING gist(geom);

-- Allinea Alembic senza rieseguire la migrazione 0001.
CREATE TABLE collettori.alembic_version (
    version_num varchar(32) NOT NULL PRIMARY KEY
);
INSERT INTO collettori.alembic_version(version_num) VALUES ('0001');

-- Solo FastAPI accede ai dati. Le autorizzazioni per utente/area restano nel backend.
DO $security$
DECLARE item record;
BEGIN
    FOR item IN SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname='collettori'
    LOOP
        EXECUTE format('REVOKE ALL ON TABLE collettori.%I FROM PUBLIC, anon, authenticated, service_role', item.tablename);
        EXECUTE format('ALTER TABLE collettori.%I ENABLE ROW LEVEL SECURITY', item.tablename);
        EXECUTE format('GRANT SELECT ON TABLE collettori.%I TO collettori_backend', item.tablename);
        IF item.tablename = 'alembic_version' THEN
            EXECUTE format('CREATE POLICY backend_read ON collettori.%I FOR SELECT TO collettori_backend USING (true)', item.tablename);
        ELSE
            EXECUTE format('GRANT INSERT, UPDATE, DELETE ON TABLE collettori.%I TO collettori_backend', item.tablename);
            EXECUTE format('CREATE POLICY backend_access ON collettori.%I FOR ALL TO collettori_backend USING (true) WITH CHECK (true)', item.tablename);
        END IF;
    END LOOP;
END $security$;
ALTER DEFAULT PRIVILEGES IN SCHEMA collettori REVOKE ALL ON TABLES FROM PUBLIC, anon, authenticated, service_role;
COMMIT;
""")
    return "\n".join(line.rstrip() for line in "\n\n".join(parts).splitlines()) + "\n"

if __name__ == "__main__":
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(render(), encoding="utf-8")
    print(OUTPUT)
