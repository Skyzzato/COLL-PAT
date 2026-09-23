-- v0.21 structural migration. No purge of historical operational data here.
BEGIN;
CREATE TABLE IF NOT EXISTS coll_pat.object_deletions(
 project_id uuid NOT NULL REFERENCES coll_pat.projects(id),id uuid NOT NULL,kind text NOT NULL,
 deleted_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(project_id,id));
CREATE TABLE IF NOT EXISTS coll_pat.retired_operations(
 operation_id uuid PRIMARY KEY,project_id uuid NOT NULL,author uuid NOT NULL,generation bigint NOT NULL,
 fingerprint text NOT NULL,receipt jsonb NOT NULL);
CREATE TABLE IF NOT EXISTS coll_pat.storage_deletions(
 project_id uuid NOT NULL,photo_id uuid NOT NULL,inspection_id uuid NOT NULL,operation_id uuid NOT NULL,
 requested_by uuid NOT NULL,storage_path text NOT NULL,confirmed_at timestamptz,
 PRIMARY KEY(project_id,photo_id));
DO $$ DECLARE t text;BEGIN FOREACH t IN ARRAY ARRAY['object_deletions','retired_operations','storage_deletions'] LOOP
 EXECUTE format('ALTER TABLE coll_pat.%I ENABLE ROW LEVEL SECURITY',t);
 EXECUTE format('REVOKE ALL ON coll_pat.%I FROM PUBLIC,anon,authenticated',t);
END LOOP;END $$;
CREATE OR REPLACE FUNCTION coll_pat.operation_hash(op jsonb) RETURNS text LANGUAGE sql IMMUTABLE SET search_path='' AS $$
 SELECT encode(sha256(convert_to(op::text,'UTF8')),'hex')
$$;
CREATE OR REPLACE FUNCTION coll_pat.retired_receipt(op jsonb) RETURNS jsonb LANGUAGE plpgsql SET search_path='' AS $$
DECLARE r coll_pat.retired_operations%ROWTYPE;BEGIN
 SELECT * INTO r FROM coll_pat.retired_operations WHERE operation_id=(op->>'operation_id')::uuid;
 IF FOUND THEN
  IF r.project_id IS DISTINCT FROM (op->>'project_id')::uuid OR r.author IS DISTINCT FROM auth.uid() OR r.fingerprint<>coll_pat.operation_hash(op) THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Operation UUID reused';END IF;
  RETURN r.receipt;
 END IF;RETURN NULL;
END $$;
CREATE OR REPLACE FUNCTION coll_pat.guard_catalog_deleted() RETURNS trigger LANGUAGE plpgsql SET search_path='' AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM coll_pat.object_deletions d WHERE d.project_id=NEW.project_id AND d.id=NEW.id)
 OR EXISTS(SELECT 1 FROM coll_pat.catalog_deletions d WHERE d.project_id=NEW.project_id AND d.id=NEW.id)
 OR EXISTS(SELECT 1 FROM jsonb_array_elements_text(coalesce(NEW.data->'collectors','[]')) c JOIN coll_pat.object_deletions d ON d.project_id=NEW.project_id AND d.id=c::uuid)
 THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Ciclo di vita eliminato: aggiornamento obsoleto';END IF;
 IF coalesce((NEW.data->>'deleted')::boolean,false) THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Usare eliminazione definitiva';END IF;
 RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION coll_pat.guard_inspection_deleted() RETURNS trigger LANGUAGE plpgsql SET search_path='' AS $$
BEGIN IF EXISTS(SELECT 1 FROM coll_pat.object_deletions WHERE project_id=NEW.project_id AND id IN (NEW.id,NEW.manhole_id)) THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Ispezione o pozzetto eliminato';END IF;RETURN NEW;END $$;
DROP TRIGGER IF EXISTS coll_pat_inspection_deleted ON coll_pat.inspections;
CREATE TRIGGER coll_pat_inspection_deleted BEFORE INSERT OR UPDATE ON coll_pat.inspections FOR EACH ROW EXECUTE FUNCTION coll_pat.guard_inspection_deleted();

CREATE OR REPLACE FUNCTION coll_pat.deletion_scope(project uuid,p_kind text,target uuid) RETURNS jsonb LANGUAGE plpgsql SET search_path='' AS $$
DECLARE removed uuid[];shared uuid[];visits uuid[];photos uuid[];fingerprint text;BEGIN
 IF p_kind NOT IN ('collector','point','inspection') THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Tipo eliminazione non valido';END IF;
 SELECT coalesce(array_agg(c.id ORDER BY c.id),'{}') INTO removed FROM coll_pat.catalog c WHERE c.project_id=project AND
 (c.id=target AND c.kind=p_kind OR p_kind='collector' AND c.kind<>'collector' AND c.data->'collectors'=jsonb_build_array(target::text)
 OR p_kind='point' AND c.kind='segment' AND (c.data->>'from_id'=target::text OR c.data->>'to_id'=target::text));
 SELECT coalesce(array_agg(c.id ORDER BY c.id),'{}') INTO shared FROM coll_pat.catalog c WHERE c.project_id=project AND p_kind='collector' AND c.kind<>'collector' AND c.data->'collectors' ? target::text AND NOT c.id=ANY(removed);
 IF EXISTS(SELECT 1 FROM coll_pat.catalog c WHERE c.project_id=project AND c.id=ANY(shared) AND c.kind='segment' AND ((c.data->>'from_id')::uuid=ANY(removed) OR (c.data->>'to_id')::uuid=ANY(removed))) THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Appartenenze condivise incoerenti';END IF;
 SELECT coalesce(array_agg(i.id ORDER BY i.id),'{}') INTO visits FROM coll_pat.inspections i WHERE i.project_id=project AND (i.manhole_id=ANY(removed) OR p_kind='inspection' AND i.id=target);
 SELECT coalesce(array_agg(p.id ORDER BY p.id),'{}') INTO photos FROM coll_pat.inspection_photos p WHERE p.project_id=project AND p.inspection_id=ANY(visits);
 SELECT coll_pat.operation_hash(jsonb_build_object('catalog',(SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id) FROM coll_pat.catalog c WHERE c.project_id=project AND c.id=ANY(removed||shared)),
 'inspections',(SELECT jsonb_agg(jsonb_build_array(i.id,i.revision) ORDER BY i.id) FROM coll_pat.inspections i WHERE i.project_id=project AND i.id=ANY(visits)),'photos',photos)) INTO fingerprint;
 RETURN jsonb_build_object('id',target,'kind',p_kind,'removed',to_jsonb(removed),'points',(SELECT coalesce(jsonb_agg(c.id ORDER BY c.id),'[]') FROM coll_pat.catalog c WHERE c.project_id=project AND c.id=ANY(removed) AND c.kind='point'),'segments',(SELECT coalesce(jsonb_agg(c.id ORDER BY c.id),'[]') FROM coll_pat.catalog c WHERE c.project_id=project AND c.id=ANY(removed) AND c.kind='segment'),'shared',to_jsonb(shared),'inspections',to_jsonb(visits),'photos',to_jsonb(photos),'token',fingerprint,'verified',true);
END $$;
CREATE OR REPLACE FUNCTION public.coll_pat_deletion_preview(p_project uuid,p_kind text,p_id uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE u uuid:=coll_pat.require_member(p_project,p_kind<>'inspection');i coll_pat.inspections%ROWTYPE;BEGIN
 IF p_kind='inspection' THEN
  SELECT * INTO i FROM coll_pat.inspections WHERE project_id=p_project AND id=p_id;
  IF FOUND AND i.status<>'BOZZA' AND i.author<>u AND i.submitted_by IS DISTINCT FROM u AND NOT EXISTS(SELECT 1 FROM coll_pat.memberships WHERE project_id=p_project AND user_id=u AND role='admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='Eliminazione riservata autore o amministratore';END IF;
 END IF;
 RETURN coll_pat.deletion_scope(p_project,p_kind,p_id);
END $$;
-- Recursively remove only deleted objects/references from shared JSON containers.
CREATE OR REPLACE FUNCTION coll_pat.scrub_deleted(v jsonb,ids text[]) RETURNS jsonb LANGUAGE plpgsql IMMUTABLE SET search_path='' AS $$
DECLARE out jsonb;entry record;child jsonb;BEGIN
 IF jsonb_typeof(v)='object' THEN
  IF v->>'id'=ANY(ids) OR v->>'inspection_id'=ANY(ids) OR v->>'manhole_id'=ANY(ids) OR v->>'photoId'=ANY(ids) THEN RETURN NULL;END IF;
  out:='{}';FOR entry IN SELECT key,value FROM jsonb_each(v) LOOP
   child:=coll_pat.scrub_deleted(entry.value,ids);IF child IS NOT NULL THEN out:=out||jsonb_build_object(entry.key,child);END IF;
  END LOOP;RETURN out;
 ELSIF jsonb_typeof(v)='array' THEN
  SELECT coalesce(jsonb_agg(x),'[]') INTO out FROM (SELECT coll_pat.scrub_deleted(value,ids) x FROM jsonb_array_elements(v)) q WHERE x IS NOT NULL;RETURN out;
 ELSIF jsonb_typeof(v)='string' AND v#>>'{}'=ANY(ids) THEN RETURN NULL;
 ELSE RETURN v;END IF;
END $$;
CREATE OR REPLACE FUNCTION coll_pat.purge_scope(project uuid,scope jsonb,op uuid,u uuid) RETURNS void LANGUAGE plpgsql SET search_path='' AS $$
DECLARE removed uuid[];visits uuid[];ids text[];r record;BEGIN
 SELECT coalesce(array_agg(v::uuid),'{}') INTO removed FROM jsonb_array_elements_text(scope->'removed') v;
 SELECT coalesce(array_agg(v::uuid),'{}') INTO visits FROM jsonb_array_elements_text(scope->'inspections') v;
 SELECT array_agg(v) INTO ids FROM jsonb_array_elements_text((scope->'removed')||(scope->'inspections')||(scope->'photos')) v;
 ids:=coalesce(ids,'{}');
 INSERT INTO coll_pat.storage_deletions(project_id,photo_id,inspection_id,operation_id,requested_by,storage_path)
 SELECT project,id,inspection_id,op,u,storage_path FROM coll_pat.inspection_photos WHERE project_id=project AND inspection_id=ANY(visits) ON CONFLICT DO NOTHING;
 INSERT INTO coll_pat.object_deletions(project_id,id,kind)
 SELECT project,id,kind FROM coll_pat.catalog WHERE project_id=project AND id=ANY(removed) ON CONFLICT DO NOTHING;
 INSERT INTO coll_pat.object_deletions(project_id,id,kind) SELECT project,id,'inspection' FROM coll_pat.inspections WHERE project_id=project AND id=ANY(visits) ON CONFLICT DO NOTHING;
 -- Receipts retain the original operation fingerprint but no recoverable deleted payload.
 FOR r IN SELECT * FROM coll_pat.receipts WHERE project_id=project AND coll_pat.scrub_deleted(operation,ids) IS DISTINCT FROM operation LOOP
  INSERT INTO coll_pat.retired_operations VALUES(r.operation_id,project,r.author,r.generation,coll_pat.operation_hash(r.operation),jsonb_build_object('operation_id',r.operation_id,'project_id',project,'generation',r.generation,'revision',r.receipt->'revision','content_deleted',true)) ON CONFLICT DO NOTHING;
  DELETE FROM coll_pat.receipts WHERE operation_id=r.operation_id;
 END LOOP;
 UPDATE coll_pat.catalog_chunks SET items=coalesce(coll_pat.scrub_deleted(items,ids),'[]') WHERE project_id=project;
 -- Reports can contain source attribute examples. Retain mappings for unaffected active imports.
 UPDATE coll_pat.imports SET provenance=provenance-'report' WHERE project_id=project AND provenance->>'source' IN (SELECT data->>'source' FROM coll_pat.catalog WHERE project_id=project AND id=ANY(removed));
 DELETE FROM coll_pat.inspection_photos WHERE project_id=project AND inspection_id=ANY(visits);
 DELETE FROM coll_pat.cancellations WHERE project_id=project AND inspection_id=ANY(visits);
 DELETE FROM coll_pat.inspections WHERE project_id=project AND id=ANY(visits);
 UPDATE coll_pat.catalog SET data=jsonb_set(data,'{collectors}',(data->'collectors')-(scope->>'id')),updated_by=u,updated_at=now() WHERE project_id=project AND id::text IN (SELECT jsonb_array_elements_text(scope->'shared'));
 DELETE FROM coll_pat.catalog WHERE project_id=project AND id=ANY(removed);
 UPDATE coll_pat.catalog SET data=coll_pat.scrub_deleted(data,ids),updated_by=u,updated_at=now() WHERE project_id=project AND coll_pat.scrub_deleted(data,ids) IS DISTINCT FROM data;
 -- Legacy archival copies are scrubbed only as part of this explicit purge, never schema install.
 UPDATE coll_pat.catalog_deletions SET original='{}',source_identity=NULL WHERE project_id=project AND id=ANY(removed);
 UPDATE coll_pat.projects SET catalog_revision=catalog_revision+1,inspection_revision=inspection_revision+1 WHERE id=project;
END $$;
CREATE OR REPLACE FUNCTION public.coll_pat_delete_permanent(operation jsonb) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE project uuid:=(operation->>'project_id')::uuid;u uuid:=coll_pat.require_member(project);p jsonb:=operation->'payload';scope jsonb;expected jsonb:=p->'scope';result jsonb;gen bigint;op uuid:=(operation->>'operation_id')::uuid;BEGIN
 SELECT generation INTO gen FROM coll_pat.projects WHERE id=project FOR UPDATE;
 IF operation->>'kind' IS DISTINCT FROM 'permanent_delete' OR (operation->>'payload_version')::integer IS DISTINCT FROM 2 OR (p->>'confirmed')::boolean IS DISTINCT FROM true THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Conferma eliminazione definitiva richiesta';END IF;
 IF gen IS DISTINCT FROM (operation->>'generation')::bigint THEN RAISE SQLSTATE 'PT409' USING MESSAGE='RESET_OBSOLETE';END IF;
 result:=coll_pat.retired_receipt(operation);IF result IS NOT NULL THEN RETURN result;END IF;
 scope:=public.coll_pat_deletion_preview(project,p->>'kind',(p->>'id')::uuid);
 IF expected IS NULL OR jsonb_typeof(expected->'removed') IS DISTINCT FROM 'array' OR jsonb_typeof(expected->'shared') IS DISTINCT FROM 'array' OR jsonb_typeof(expected->'inspections') IS DISTINCT FROM 'array' OR jsonb_typeof(expected->'photos') IS DISTINCT FROM 'array' OR NOT ((scope->'removed') <@ (expected->'removed')) OR NOT ((scope->'shared') <@ (expected->'shared')) OR NOT ((scope->'inspections') <@ (expected->'inspections')) OR NOT ((scope->'photos') <@ (expected->'photos'))
 OR expected ? 'token' AND expected->>'token'<>scope->>'token'  THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Ambito eliminazione cambiato: nuova anteprima e conferma richieste';END IF;
 PERFORM coll_pat.purge_scope(project,scope,op,u);
 -- Also retire offline identifiers absent remotely: old queues must not resurrect them.
 INSERT INTO coll_pat.object_deletions VALUES(project,(p->>'id')::uuid,p->>'kind',now()) ON CONFLICT DO NOTHING;
 result:=jsonb_build_object('operation_id',op,'project_id',project,'generation',gen,'database_deleted',true,'storage_pending',(SELECT count(*) FROM coll_pat.storage_deletions WHERE project_id=project AND operation_id=op AND confirmed_at IS NULL),'server_at',now());
 INSERT INTO coll_pat.retired_operations VALUES(op,project,u,gen,coll_pat.operation_hash(operation),result);
 RETURN result;
END $$;
-- Old clients cannot silently execute the former history-preserving action.
CREATE OR REPLACE FUNCTION public.coll_pat_delete_collector(operation jsonb) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN PERFORM coll_pat.require_member((operation->>'project_id')::uuid,true);RAISE SQLSTATE 'PT409' USING MESSAGE='Eliminazione definitiva: aggiornare app e riconfermare ambito';END $$;
CREATE OR REPLACE FUNCTION public.coll_pat_deleted(p_project uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE u uuid:=coll_pat.require_member(p_project);BEGIN
 RETURN jsonb_build_object('items',(SELECT coalesce(jsonb_agg(jsonb_build_object('id',id,'kind',kind,'deleted',true)),'[]') FROM coll_pat.object_deletions WHERE project_id=p_project));
END $$;
CREATE OR REPLACE FUNCTION public.coll_pat_storage_pending(p_project uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE u uuid:=coll_pat.require_member(p_project);BEGIN
 RETURN jsonb_build_object('items',(SELECT coalesce(jsonb_agg(jsonb_build_object('photo_id',d.photo_id,'inspection_id',d.inspection_id,'operation_id',d.operation_id,'path',d.storage_path)),'[]') FROM coll_pat.storage_deletions d WHERE d.project_id=p_project AND d.confirmed_at IS NULL AND (d.requested_by=u OR EXISTS(SELECT 1 FROM coll_pat.memberships WHERE project_id=p_project AND user_id=u AND role='admin'))));
END $$;
CREATE OR REPLACE FUNCTION public.coll_pat_storage_confirm(p_project uuid,p_photo uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE u uuid:=coll_pat.require_member(p_project);d coll_pat.storage_deletions%ROWTYPE;found_object boolean;BEGIN
 SELECT * INTO d FROM coll_pat.storage_deletions WHERE project_id=p_project AND photo_id=p_photo;
 IF NOT FOUND OR d.requested_by<>u AND NOT EXISTS(SELECT 1 FROM coll_pat.memberships WHERE project_id=p_project AND user_id=u AND role='admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='Storage cleanup denied';END IF;
 EXECUTE 'SELECT EXISTS(SELECT 1 FROM storage.objects WHERE bucket_id=$1 AND name=$2)' INTO found_object USING 'coll-pat-photos',d.storage_path;
 IF found_object THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Allegato ancora presente nello Storage';END IF;
 UPDATE coll_pat.storage_deletions SET confirmed_at=now() WHERE project_id=p_project AND photo_id=p_photo;
 RETURN jsonb_build_object('id',p_photo,'deleted',true);
END $$;
CREATE OR REPLACE FUNCTION public.coll_pat_photo_delete_access(object_name text) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT EXISTS(SELECT 1 FROM coll_pat.storage_deletions d JOIN coll_pat.memberships m ON m.project_id=d.project_id AND m.user_id=auth.uid() WHERE d.storage_path=object_name AND (d.requested_by=auth.uid() OR m.role='admin'))
$$;
DO $$ BEGIN IF to_regclass('storage.objects') IS NOT NULL THEN
 DROP POLICY IF EXISTS coll_pat_photos_delete ON storage.objects;
 CREATE POLICY coll_pat_photos_delete ON storage.objects FOR DELETE TO authenticated USING(bucket_id='coll-pat-photos' AND public.coll_pat_photo_delete_access(name));
 DROP POLICY IF EXISTS coll_pat_photos_delete_read ON storage.objects;
 CREATE POLICY coll_pat_photos_delete_read ON storage.objects FOR SELECT TO authenticated USING(bucket_id='coll-pat-photos' AND public.coll_pat_photo_delete_access(name));
END IF;END $$;
-- Preserve the existing apply path; replay checks work after payloads have been scrubbed.
CREATE OR REPLACE FUNCTION public.coll_pat_apply(operation jsonb) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb;project uuid:=(operation->>'project_id')::uuid;BEGIN
 PERFORM coll_pat.require_member(project);PERFORM 1 FROM coll_pat.projects WHERE id=project FOR UPDATE;
 result:=coll_pat.retired_receipt(operation);IF result IS NOT NULL THEN RETURN result;END IF;
 IF operation->>'kind'='inspection' THEN RAISE SQLSTATE 'PT426' USING MESSAGE='Aggiornare il protocollo ispezioni';END IF;
 IF operation->>'kind'='cancel' AND EXISTS(SELECT 1 FROM coll_pat.inspections WHERE project_id=project AND id=(operation->'payload'->>'inspection_id')::uuid AND submitted_by=auth.uid() AND author<>auth.uid()) THEN RETURN coll_pat.cancel_v014(operation);END IF;
 RETURN coll_pat.apply_v013(operation);
END $$;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA coll_pat FROM PUBLIC,anon,authenticated;
DO $$ DECLARE f record;BEGIN FOR f IN SELECT p.oid::regprocedure signature FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname IN ('coll_pat_delete_permanent','coll_pat_deletion_preview','coll_pat_deleted','coll_pat_storage_pending','coll_pat_storage_confirm','coll_pat_photo_delete_access') LOOP
 EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC,anon',f.signature);EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO authenticated',f.signature);
END LOOP;END $$;
NOTIFY pgrst,'reload schema';
COMMIT;
