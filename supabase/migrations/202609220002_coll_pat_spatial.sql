-- PostGIS may already live in public, extensions or another schema. Qualify it explicitly.
BEGIN;
CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA extensions;
DO $migration$
DECLARE ns text;
BEGIN
 SELECT n.nspname INTO ns FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace WHERE e.extname='postgis';
 EXECUTE format('ALTER TABLE coll_pat.catalog ADD COLUMN geometry %I.geometry(Geometry,4326)',ns);
 EXECUTE 'CREATE INDEX coll_pat_catalog_geometry ON coll_pat.catalog USING gist(geometry)';
 EXECUTE format($ddl$
 CREATE FUNCTION coll_pat.catalog_geometry() RETURNS trigger LANGUAGE plpgsql SET search_path='' AS $body$
 BEGIN
  IF NEW.kind='point' THEN
   NEW.geometry:=%1$I.st_setsrid(%1$I.st_makepoint((NEW.data->>'longitude')::double precision,(NEW.data->>'latitude')::double precision),4326);
  ELSIF NEW.kind='segment' THEN
   NEW.geometry:=%1$I.st_setsrid(%1$I.st_geomfromgeojson((NEW.data->'geometry')::text),4326);
   IF NOT %1$I.st_isvalid(NEW.geometry) OR %1$I.st_isempty(NEW.geometry) OR %1$I.geometrytype(NEW.geometry) NOT IN ('LINESTRING','MULTILINESTRING') THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid linear geometry'; END IF;
   NEW.data:=jsonb_set(NEW.data,'{length_m}',to_jsonb(%1$I.st_length(NEW.geometry::%1$I.geography,false)));
  ELSE NEW.geometry:=NULL; END IF;
  RETURN NEW;
 END $body$;
 $ddl$,ns);
 EXECUTE format($ddl$
 CREATE FUNCTION coll_pat.refresh_lengths(p_project uuid) RETURNS void LANGUAGE plpgsql SET search_path='' AS $body$
 DECLARE c record; metres double precision; estimated boolean;
 BEGIN
  FOR c IN SELECT id,data FROM coll_pat.catalog WHERE project_id=p_project AND kind='collector' AND data->>'length_source'<>'DECLARED' LOOP
   SELECT %1$I.st_length(%1$I.st_unaryunion(%1$I.st_collect(geometry))::%1$I.geography,false),bool_or((data->>'schematic')::boolean)
   INTO metres,estimated FROM coll_pat.catalog WHERE project_id=p_project AND kind='segment' AND data->'collectors' ? c.id::text;
   UPDATE coll_pat.catalog SET data=data||jsonb_build_object('length_m',metres,'length_source',CASE WHEN metres IS NULL THEN 'UNAVAILABLE' WHEN estimated THEN 'ESTIMATED' ELSE 'MEASURED' END)
   WHERE project_id=p_project AND id=c.id;
  END LOOP;
 END $body$;
 $ddl$,ns);
END $migration$;
CREATE TRIGGER coll_pat_geometry BEFORE INSERT OR UPDATE OF data ON coll_pat.catalog FOR EACH ROW EXECUTE FUNCTION coll_pat.catalog_geometry();
REVOKE ALL ON FUNCTION coll_pat.catalog_geometry() FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION coll_pat.refresh_lengths(uuid) FROM PUBLIC,anon,authenticated;
NOTIFY pgrst,'reload schema';
COMMIT;
