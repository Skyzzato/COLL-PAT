-- v0.2: additive lifecycle, durable deletion and publication-order compatibility.
BEGIN;
CREATE OR REPLACE FUNCTION coll_pat.version_parts(v text) RETURNS integer[] LANGUAGE plpgsql IMMUTABLE SET search_path='' AS $$
DECLARE a text[]; minor integer;
BEGIN
 IF coalesce(v,'') !~ '^[0-9]+\.[0-9]+(\.[0-9]+)?(-demo)?$' THEN RETURN ARRAY[0,0,0]; END IF;
 a:=string_to_array(replace(v,'-demo',''),'.');minor:=a[2]::integer;
 -- The published v0.2 follows v0.16 (Android build 17). Names stay unchanged.
 IF a[1]::integer=0 AND minor=2 THEN minor:=17; END IF;
 RETURN ARRAY[a[1]::integer,minor,coalesce(a[3]::integer,0)];
END $$;

CREATE TABLE IF NOT EXISTS coll_pat.catalog_deletions (
 project_id uuid NOT NULL REFERENCES coll_pat.projects(id), id uuid NOT NULL,
 kind text NOT NULL, source_identity text, deleted_at timestamptz NOT NULL DEFAULT now(),
 deleted_by uuid NOT NULL REFERENCES auth.users(id), original jsonb NOT NULL,
 PRIMARY KEY(project_id,id)
);
CREATE UNIQUE INDEX IF NOT EXISTS coll_pat_deleted_source ON coll_pat.catalog_deletions(project_id,source_identity) WHERE source_identity IS NOT NULL;
ALTER TABLE coll_pat.catalog_deletions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON coll_pat.catalog_deletions FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION coll_pat.guard_catalog_deleted() RETURNS trigger LANGUAGE plpgsql SET search_path='' AS $$
DECLARE cid text;
BEGIN
 IF EXISTS(SELECT 1 FROM coll_pat.catalog_deletions d WHERE d.project_id=NEW.project_id AND
    (d.id=NEW.id OR d.source_identity=NEW.data->>'source_identity')) THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='Elemento eliminato: ripubblicazione vietata'; END IF;
 IF coalesce((NEW.data->>'deleted')::boolean,false) THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Usare la cancellazione autorizzata del collettore'; END IF;
 FOR cid IN SELECT jsonb_array_elements_text(coalesce(NEW.data->'collectors','[]')) LOOP
   IF EXISTS(SELECT 1 FROM coll_pat.catalog_deletions d WHERE d.project_id=NEW.project_id AND d.id=cid::uuid)
     THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Collettore eliminato'; END IF;
 END LOOP;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS coll_pat_guard_deleted ON coll_pat.catalog;
CREATE TRIGGER coll_pat_guard_deleted BEFORE INSERT OR UPDATE OF data ON coll_pat.catalog FOR EACH ROW EXECUTE FUNCTION coll_pat.guard_catalog_deleted();

CREATE OR REPLACE FUNCTION coll_pat.check_next_points() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE cid text;current_data jsonb;
BEGIN
 SELECT data INTO current_data FROM coll_pat.catalog WHERE project_id=NEW.project_id AND id=NEW.id;
 IF current_data IS NULL OR EXISTS(SELECT 1 FROM coll_pat.catalog_deletions WHERE project_id=NEW.project_id AND id=NEW.id) THEN RETURN NULL; END IF;
 IF NEW.kind='point' THEN
   FOR cid IN SELECT jsonb_array_elements_text(coalesce(current_data->'next_ids','[]')) LOOP
     IF cid=NEW.id::text OR NOT EXISTS(SELECT 1 FROM coll_pat.catalog p WHERE p.project_id=NEW.project_id AND p.id=cid::uuid AND p.kind='point' AND coll_pat.active_catalog(NEW.project_id,p)
        AND EXISTS(SELECT 1 FROM jsonb_array_elements_text(current_data->'collectors') member WHERE p.data->'collectors' ? member))
       THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Successivo sconosciuto o non appartenente al collettore'; END IF;
   END LOOP;
 END IF;RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS coll_pat_next_points ON coll_pat.catalog;
CREATE CONSTRAINT TRIGGER coll_pat_next_points AFTER INSERT OR UPDATE ON coll_pat.catalog DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION coll_pat.check_next_points();

CREATE OR REPLACE FUNCTION coll_pat.active_catalog(p_project uuid,c coll_pat.catalog) RETURNS boolean LANGUAGE sql STABLE SET search_path='' AS $$
 SELECT NOT EXISTS(SELECT 1 FROM coll_pat.catalog_deletions d WHERE d.project_id=p_project AND d.id=c.id)
 AND CASE WHEN c.kind='collector' THEN NOT coalesce((c.data->>'archived')::boolean,false)
 ELSE NOT coalesce((c.data->>'archived')::boolean,false) AND EXISTS(SELECT 1 FROM coll_pat.catalog parent
 WHERE parent.project_id=p_project AND parent.kind='collector' AND c.data->'collectors' ? parent.id::text
 AND NOT coalesce((parent.data->>'archived')::boolean,false)
 AND NOT EXISTS(SELECT 1 FROM coll_pat.catalog_deletions d WHERE d.project_id=p_project AND d.id=parent.id)) END
$$;

DO $migration$
DECLARE ns text;
BEGIN
 SELECT n.nspname INTO ns FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace WHERE e.extname='postgis';
 EXECUTE format($ddl$
 CREATE OR REPLACE FUNCTION coll_pat.refresh_lengths(p_project uuid) RETURNS void LANGUAGE plpgsql SET search_path='' AS $body$
 DECLARE c record;metres double precision;estimated boolean;
 BEGIN
  FOR c IN SELECT id,data FROM coll_pat.catalog item WHERE project_id=p_project AND kind='collector' AND data->>'length_source'<>'DECLARED' AND coll_pat.active_catalog(p_project,item) LOOP
   SELECT %1$I.st_length(%1$I.st_unaryunion(%1$I.st_collect(geometry))::%1$I.geography,false),bool_or((data->>'schematic')::boolean) INTO metres,estimated
   FROM coll_pat.catalog item WHERE project_id=p_project AND kind='segment' AND data->'collectors' ? c.id::text AND coll_pat.active_catalog(p_project,item);
   UPDATE coll_pat.catalog SET data=data||jsonb_build_object('length_m',metres,'length_source',CASE WHEN metres IS NULL THEN 'UNAVAILABLE' WHEN estimated THEN 'ESTIMATED' ELSE 'MEASURED' END) WHERE project_id=p_project AND id=c.id;
  END LOOP;
 END $body$;
 $ddl$,ns);
END $migration$;

CREATE OR REPLACE FUNCTION public.coll_pat_delete_collector(operation jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE project uuid:=(operation->>'project_id')::uuid;u uuid:=coll_pat.require_member(project,true);
 op uuid:=(operation->>'operation_id')::uuid;cid uuid:=(operation->'payload'->>'id')::uuid;
 gen bigint;old coll_pat.receipts%ROWTYPE;asset coll_pat.catalog%ROWTYPE;row coll_pat.catalog%ROWTYPE;
 removed uuid[];kept uuid[];result jsonb;point_history boolean;
BEGIN
 IF operation->>'kind' IS DISTINCT FROM 'catalog_delete' OR (operation->>'payload_version')::integer IS DISTINCT FROM 2
 OR (operation->'payload'->>'confirmed')::boolean IS DISTINCT FROM true THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Conferma eliminazione richiesta'; END IF;
 SELECT generation INTO gen FROM coll_pat.projects WHERE id=project FOR UPDATE;
 IF (operation->>'generation')::bigint IS DISTINCT FROM gen THEN RAISE SQLSTATE 'PT409' USING MESSAGE='RESET_OBSOLETE'; END IF;
 SELECT * INTO old FROM coll_pat.receipts WHERE operation_id=op;
 IF FOUND THEN
   IF old.author<>u OR old.project_id<>project OR old.operation<>operation THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Operation UUID reused'; END IF;
   RETURN old.receipt;
 END IF;
 SELECT * INTO asset FROM coll_pat.catalog WHERE project_id=project AND id=cid AND kind='collector';
 IF NOT FOUND AND NOT EXISTS(SELECT 1 FROM coll_pat.catalog_deletions WHERE project_id=project AND id=cid) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='Collettore assente'; END IF;
 SELECT coalesce(array_agg(id),'{}') INTO removed FROM coll_pat.catalog WHERE project_id=project AND
 (id=cid OR (kind<>'collector' AND data->'collectors'=jsonb_build_array(cid::text)));
 SELECT coalesce(array_agg(id),'{}') INTO kept FROM coll_pat.catalog WHERE project_id=project AND kind<>'collector' AND data->'collectors' ? cid::text AND NOT id=ANY(removed);
 -- Preserve shared points and lines; no silent loss of another collector's topology.
 IF EXISTS(SELECT 1 FROM coll_pat.catalog WHERE project_id=project AND kind='segment' AND id=ANY(kept)
    AND ((data->>'from_id')::uuid=ANY(removed) OR (data->>'to_id')::uuid=ANY(removed))) THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='Relazioni condivise incoerenti: correggere le appartenenze prima di eliminare'; END IF;
 FOR row IN SELECT * FROM coll_pat.catalog WHERE project_id=project AND id=ANY(kept) LOOP
   UPDATE coll_pat.catalog SET data=jsonb_set(data,'{collectors}',(data->'collectors')-cid::text),updated_by=u,updated_at=now() WHERE project_id=project AND id=row.id;
 END LOOP;
 -- Remove outgoing manual references from surviving points, never rewrite official geometries.
 UPDATE coll_pat.catalog SET data=jsonb_set(data,'{next_ids}',(SELECT coalesce(jsonb_agg(n),'[]') FROM jsonb_array_elements_text(data->'next_ids') n WHERE NOT n::uuid=ANY(removed))),updated_by=u
 WHERE project_id=project AND kind='point' AND NOT id=ANY(removed) AND data ? 'next_ids'
 AND NOT EXISTS(SELECT 1 FROM coll_pat.catalog_deletions d WHERE d.project_id=project AND d.id=coll_pat.catalog.id);
 FOR row IN SELECT * FROM coll_pat.catalog WHERE project_id=project AND id=ANY(removed) LOOP
   INSERT INTO coll_pat.catalog_deletions(project_id,id,kind,source_identity,deleted_by,original)
   VALUES(project,row.id,row.kind,nullif(row.data->>'source_identity',''),u,row.data) ON CONFLICT(project_id,id) DO NOTHING;
 END LOOP;
 -- Preserve catalogue rows referenced by history. The API hides them using tombstones.
 SELECT EXISTS(SELECT 1 FROM coll_pat.inspections WHERE project_id=project AND manhole_id=ANY(removed)) INTO point_history;
 DELETE FROM coll_pat.catalog c WHERE c.project_id=project AND c.id=ANY(removed)
 AND NOT EXISTS(SELECT 1 FROM coll_pat.inspections i WHERE i.project_id=project AND i.manhole_id=c.id)
 AND NOT (c.kind='collector' AND point_history);
 UPDATE coll_pat.projects SET catalog_revision=catalog_revision+1 WHERE id=project;
 result:=jsonb_build_object('operation_id',op,'project_id',project,'generation',gen,'deleted',to_jsonb(removed),'history_preserved',true,'server_at',now());
 INSERT INTO coll_pat.receipts(operation_id,project_id,author,generation,operation,receipt) VALUES(op,project,u,gen,operation,result);
 RETURN result;
END $$;
REVOKE ALL ON FUNCTION public.coll_pat_delete_collector(jsonb) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.coll_pat_delete_collector(jsonb) TO authenticated;

CREATE OR REPLACE FUNCTION public.coll_pat_catalog(p_project uuid,p_offset integer DEFAULT 0,p_revision bigint DEFAULT NULL) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE u uuid:=coll_pat.require_member(p_project);rev bigint;result jsonb;total integer;tombstones jsonb;deleted jsonb;
BEGIN
 IF p_offset<0 OR p_offset>100000 THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid catalog offset'; END IF;
 SELECT catalog_revision INTO rev FROM coll_pat.projects WHERE id=p_project FOR SHARE;
 IF p_revision IS NOT NULL AND p_revision<>rev THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Catalog changed while paging'; END IF;
 SELECT count(*) INTO total FROM coll_pat.catalog c WHERE project_id=p_project AND coll_pat.active_catalog(p_project,c);
 SELECT coalesce(jsonb_agg(jsonb_build_object('kind',kind,'data',data)),'[]') INTO result FROM
 (SELECT kind,data FROM coll_pat.catalog c WHERE project_id=p_project AND coll_pat.active_catalog(p_project,c) ORDER BY id LIMIT 200 OFFSET p_offset) q;
 SELECT coalesce(jsonb_agg(jsonb_build_object('kind',kind,'data',data)),'[]') INTO tombstones FROM coll_pat.catalog c
 WHERE project_id=p_project AND kind='collector' AND coalesce((data->>'archived')::boolean,false) AND p_offset=0
 AND NOT EXISTS(SELECT 1 FROM coll_pat.catalog_deletions d WHERE d.project_id=p_project AND d.id=c.id);
 SELECT coalesce(jsonb_agg(jsonb_build_object('id',id,'kind',kind,'source_identity',source_identity,'deleted',true,'deleted_at',deleted_at)),'[]') INTO deleted
 FROM coll_pat.catalog_deletions WHERE project_id=p_project AND p_offset=0;
 RETURN jsonb_build_object('items',result,'archived',tombstones,'deleted',deleted,'revision',rev,'has_more',p_offset+jsonb_array_length(result)<total,'total',total);
END $$;
REVOKE ALL ON FUNCTION coll_pat.guard_catalog_deleted() FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION coll_pat.check_next_points() FROM PUBLIC,anon,authenticated;
NOTIFY pgrst,'reload schema';
COMMIT;
