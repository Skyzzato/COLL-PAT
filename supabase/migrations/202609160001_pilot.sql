-- Pilota 0.1: database Supabase dietro FastAPI, NON accesso diretto mobile.
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


CREATE TABLE collettori.areas (
	id VARCHAR(80) NOT NULL,
	name VARCHAR NOT NULL,
	synthetic BOOLEAN NOT NULL,
	PRIMARY KEY (id)
);

CREATE TABLE collettori.companies (
	id VARCHAR(36) NOT NULL,
	name VARCHAR NOT NULL,
	PRIMARY KEY (id)
);

CREATE TABLE collettori.collectors (
	id VARCHAR(36) NOT NULL,
	area_id VARCHAR(80) NOT NULL,
	code VARCHAR NOT NULL,
	PRIMARY KEY (id),
	UNIQUE (area_id, code)
);

ALTER TABLE collettori.collectors ADD FOREIGN KEY(area_id) REFERENCES collettori.areas (id);

CREATE TABLE collettori.datasets (
	id VARCHAR(36) NOT NULL,
	area_id VARCHAR(80) NOT NULL,
	created_at VARCHAR NOT NULL,
	payload JSON NOT NULL,
	source_path VARCHAR NOT NULL,
	report JSON NOT NULL,
	sha256 VARCHAR NOT NULL,
	PRIMARY KEY (id)
);

ALTER TABLE collettori.datasets ADD FOREIGN KEY(area_id) REFERENCES collettori.areas (id);

CREATE INDEX ix_collettori_datasets_area_id ON collettori.datasets (area_id);

CREATE TABLE collettori.manholes (
	id VARCHAR(36) NOT NULL,
	area_id VARCHAR(80) NOT NULL,
	source_key VARCHAR NOT NULL,
	PRIMARY KEY (id),
	UNIQUE (area_id, source_key)
);

ALTER TABLE collettori.manholes ADD FOREIGN KEY(area_id) REFERENCES collettori.areas (id);

CREATE INDEX ix_collettori_manholes_area_id ON collettori.manholes (area_id);

CREATE TABLE collettori.users (
	id VARCHAR(36) NOT NULL,
	username VARCHAR(120) NOT NULL,
	password_hash VARCHAR NOT NULL,
	role VARCHAR NOT NULL,
	company_id VARCHAR(36) NOT NULL,
	active BOOLEAN NOT NULL,
	PRIMARY KEY (id),
	UNIQUE (username)
);

ALTER TABLE collettori.users ADD FOREIGN KEY(company_id) REFERENCES collettori.companies (id);

CREATE TABLE collettori.area_grants (
	user_id VARCHAR(36) NOT NULL,
	area_id VARCHAR(80) NOT NULL,
	PRIMARY KEY (user_id, area_id)
);

ALTER TABLE collettori.area_grants ADD FOREIGN KEY(area_id) REFERENCES collettori.areas (id);

ALTER TABLE collettori.area_grants ADD FOREIGN KEY(user_id) REFERENCES collettori.users (id);

CREATE TABLE collettori.audit (
	id VARCHAR(36) NOT NULL,
	author_id VARCHAR(36) NOT NULL,
	kind VARCHAR NOT NULL,
	target_id VARCHAR NOT NULL,
	at VARCHAR NOT NULL,
	payload JSON NOT NULL,
	PRIMARY KEY (id)
);

ALTER TABLE collettori.audit ADD FOREIGN KEY(author_id) REFERENCES collettori.users (id);

CREATE TABLE collettori.deadlines (
	id VARCHAR(36) NOT NULL,
	area_id VARCHAR(80) NOT NULL,
	manhole_id VARCHAR(36) NOT NULL,
	company_id VARCHAR(36) NOT NULL,
	due_at VARCHAR NOT NULL,
	source VARCHAR NOT NULL,
	created_at VARCHAR NOT NULL,
	PRIMARY KEY (id)
);

ALTER TABLE collettori.deadlines ADD FOREIGN KEY(area_id) REFERENCES collettori.areas (id);

ALTER TABLE collettori.deadlines ADD FOREIGN KEY(company_id) REFERENCES collettori.companies (id);

ALTER TABLE collettori.deadlines ADD FOREIGN KEY(manhole_id) REFERENCES collettori.manholes (id);

CREATE INDEX ix_collettori_deadlines_manhole_id ON collettori.deadlines (manhole_id);

CREATE TABLE collettori.inspections (
	id VARCHAR(36) NOT NULL,
	manhole_id VARCHAR(36) NOT NULL,
	user_id VARCHAR(36) NOT NULL,
	company_id VARCHAR(36) NOT NULL,
	area_id VARCHAR(80) NOT NULL,
	dataset_id VARCHAR(36) NOT NULL,
	device_id VARCHAR NOT NULL,
	synthetic BOOLEAN NOT NULL,
	current_revision INTEGER NOT NULL,
	received_at VARCHAR NOT NULL,
	PRIMARY KEY (id)
);

ALTER TABLE collettori.inspections ADD FOREIGN KEY(area_id) REFERENCES collettori.areas (id);

ALTER TABLE collettori.inspections ADD FOREIGN KEY(company_id) REFERENCES collettori.companies (id);

ALTER TABLE collettori.inspections ADD FOREIGN KEY(dataset_id) REFERENCES collettori.datasets (id);

ALTER TABLE collettori.inspections ADD FOREIGN KEY(manhole_id) REFERENCES collettori.manholes (id);

ALTER TABLE collettori.inspections ADD FOREIGN KEY(user_id) REFERENCES collettori.users (id);

CREATE INDEX ix_collettori_inspections_area_id ON collettori.inspections (area_id);

CREATE INDEX ix_collettori_inspections_manhole_id ON collettori.inspections (manhole_id);

CREATE INDEX ix_collettori_inspections_user_id ON collettori.inspections (user_id);

CREATE TABLE collettori.login_sessions (
	id VARCHAR(36) NOT NULL,
	user_id VARCHAR(36) NOT NULL,
	device_id VARCHAR NOT NULL,
	access_hash VARCHAR(64) NOT NULL,
	refresh_hash VARCHAR(64) NOT NULL,
	access_until VARCHAR NOT NULL,
	refresh_until VARCHAR NOT NULL,
	PRIMARY KEY (id),
	UNIQUE (access_hash),
	UNIQUE (refresh_hash)
);

ALTER TABLE collettori.login_sessions ADD FOREIGN KEY(user_id) REFERENCES collettori.users (id);

CREATE INDEX ix_collettori_login_sessions_user_id ON collettori.login_sessions (user_id);

CREATE TABLE collettori.reports (
	id VARCHAR(36) NOT NULL,
	author_id VARCHAR(36) NOT NULL,
	area_id VARCHAR(80) NOT NULL,
	period VARCHAR NOT NULL,
	extracted_at VARCHAR NOT NULL,
	snapshot JSON NOT NULL,
	PRIMARY KEY (id)
);

ALTER TABLE collettori.reports ADD FOREIGN KEY(area_id) REFERENCES collettori.areas (id);

ALTER TABLE collettori.reports ADD FOREIGN KEY(author_id) REFERENCES collettori.users (id);

CREATE TABLE collettori.rules (
	id VARCHAR(80) NOT NULL,
	created_at VARCHAR NOT NULL,
	author_id VARCHAR(36),
	parameters JSON NOT NULL,
	PRIMARY KEY (id)
);

ALTER TABLE collettori.rules ADD FOREIGN KEY(author_id) REFERENCES collettori.users (id);

CREATE TABLE collettori.segments (
	id VARCHAR(36) NOT NULL,
	area_id VARCHAR(80) NOT NULL,
	collector_id VARCHAR(36) NOT NULL,
	source_key VARCHAR NOT NULL,
	PRIMARY KEY (id),
	UNIQUE (area_id, source_key)
);

ALTER TABLE collettori.segments ADD FOREIGN KEY(area_id) REFERENCES collettori.areas (id);

ALTER TABLE collettori.segments ADD FOREIGN KEY(collector_id) REFERENCES collettori.collectors (id);

CREATE TABLE collettori.sync_operations (
	id VARCHAR(36) NOT NULL,
	user_id VARCHAR(36) NOT NULL,
	digest VARCHAR NOT NULL,
	receipt JSON NOT NULL,
	PRIMARY KEY (id)
);

ALTER TABLE collettori.sync_operations ADD FOREIGN KEY(user_id) REFERENCES collettori.users (id);

CREATE TABLE collettori.anomalies (
	id VARCHAR(36) NOT NULL,
	inspection_id VARCHAR(36) NOT NULL,
	area_id VARCHAR(80) NOT NULL,
	priority VARCHAR NOT NULL,
	description VARCHAR NOT NULL,
	state VARCHAR NOT NULL,
	PRIMARY KEY (id)
);

ALTER TABLE collettori.anomalies ADD FOREIGN KEY(area_id) REFERENCES collettori.areas (id);

ALTER TABLE collettori.anomalies ADD FOREIGN KEY(inspection_id) REFERENCES collettori.inspections (id);

CREATE INDEX ix_collettori_anomalies_area_id ON collettori.anomalies (area_id);

CREATE INDEX ix_collettori_anomalies_inspection_id ON collettori.anomalies (inspection_id);

CREATE TABLE collettori.connections (
	dataset_id VARCHAR(36) NOT NULL,
	segment_id VARCHAR(36) NOT NULL,
	manhole_id VARCHAR(36) NOT NULL,
	endpoint VARCHAR(10) NOT NULL,
	PRIMARY KEY (dataset_id, segment_id, manhole_id, endpoint)
);

ALTER TABLE collettori.connections ADD FOREIGN KEY(dataset_id) REFERENCES collettori.datasets (id);

ALTER TABLE collettori.connections ADD FOREIGN KEY(manhole_id) REFERENCES collettori.manholes (id);

ALTER TABLE collettori.connections ADD FOREIGN KEY(segment_id) REFERENCES collettori.segments (id);

CREATE TABLE collettori.location_events (
	id VARCHAR(36) NOT NULL,
	inspection_id VARCHAR(36) NOT NULL,
	payload JSON NOT NULL,
	server_evaluation JSON NOT NULL,
	PRIMARY KEY (id)
);

ALTER TABLE collettori.location_events ADD FOREIGN KEY(inspection_id) REFERENCES collettori.inspections (id);

CREATE INDEX ix_collettori_location_events_inspection_id ON collettori.location_events (inspection_id);

CREATE TABLE collettori.revisions (
	inspection_id VARCHAR(36) NOT NULL,
	number INTEGER NOT NULL,
	author_id VARCHAR(36) NOT NULL,
	reason VARCHAR NOT NULL,
	received_at VARCHAR NOT NULL,
	payload JSON NOT NULL,
	server_evaluation JSON NOT NULL,
	PRIMARY KEY (inspection_id, number)
);

ALTER TABLE collettori.revisions ADD FOREIGN KEY(author_id) REFERENCES collettori.users (id);

ALTER TABLE collettori.revisions ADD FOREIGN KEY(inspection_id) REFERENCES collettori.inspections (id);


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
