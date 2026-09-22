-- v0.14, additive and repeatable. Run as database owner, never from the APK.
BEGIN;
CREATE TABLE IF NOT EXISTS coll_pat.app_config (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton), latest_version text NOT NULL,
 minimum_supported_version text NOT NULL, message text NOT NULL DEFAULT '', updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO coll_pat.app_config(singleton,latest_version,minimum_supported_version,message)
 VALUES(true,'0.14','0.14','Aggiorna COLL-PAT alla versione supportata.') ON CONFLICT DO NOTHING;
ALTER TABLE coll_pat.app_config ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON coll_pat.app_config FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION coll_pat.version_parts(v text) RETURNS integer[] LANGUAGE plpgsql IMMUTABLE SET search_path='' AS $$
DECLARE a text[];
BEGIN
 IF coalesce(v,'') !~ '^[0-9]+\.[0-9]+(\.[0-9]+)?(-demo)?$' THEN RETURN ARRAY[0,0,0]; END IF;
 a:=string_to_array(replace(v,'-demo',''),'.');RETURN ARRAY[a[1]::integer,a[2]::integer,coalesce(a[3]::integer,0)];
END $$;
CREATE OR REPLACE FUNCTION public.coll_pat_version() RETURNS jsonb LANGUAGE sql SECURITY DEFINER SET search_path='' AS $$
 SELECT to_jsonb(c)-'singleton' FROM coll_pat.app_config c WHERE singleton
$$;
CREATE OR REPLACE FUNCTION coll_pat.require_member(p_project uuid,p_admin boolean DEFAULT false) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE u uuid:=auth.uid();r text;v text;
BEGIN
 IF u IS NULL THEN RAISE SQLSTATE 'PT401' USING MESSAGE='Supabase Auth required'; END IF;
 v:=coalesce(nullif(current_setting('request.headers',true),''),'{}')::jsonb->>'x-coll-pat-version';
 IF coll_pat.version_parts(v)<(SELECT coll_pat.version_parts(minimum_supported_version) FROM coll_pat.app_config WHERE singleton) THEN
   RAISE SQLSTATE 'PT426' USING MESSAGE='Versione COLL-PAT non supportata'; END IF;
 SELECT role INTO r FROM coll_pat.memberships WHERE project_id=p_project AND user_id=u;
 IF r IS NULL OR (p_admin AND r<>'admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='Project permission denied'; END IF;
 RETURN u;
END $$;

ALTER TABLE coll_pat.inspections DROP CONSTRAINT IF EXISTS inspections_status_check;
ALTER TABLE coll_pat.inspections ADD CONSTRAINT inspections_status_check CHECK(status IN ('BOZZA','COMPLETO','IMPEDITO'));
ALTER TABLE coll_pat.inspections ADD COLUMN IF NOT EXISTS created_by uuid REFERENCES auth.users(id);
ALTER TABLE coll_pat.inspections ADD COLUMN IF NOT EXISTS created_at timestamptz;
ALTER TABLE coll_pat.inspections ADD COLUMN IF NOT EXISTS updated_by uuid REFERENCES auth.users(id);
ALTER TABLE coll_pat.inspections ADD COLUMN IF NOT EXISTS updated_at timestamptz;
ALTER TABLE coll_pat.inspections ADD COLUMN IF NOT EXISTS submitted_by uuid REFERENCES auth.users(id);
ALTER TABLE coll_pat.inspections ADD COLUMN IF NOT EXISTS submitted_at timestamptz;
ALTER TABLE coll_pat.inspections ADD COLUMN IF NOT EXISTS revision bigint NOT NULL DEFAULT 1;
UPDATE coll_pat.inspections SET created_by=author,created_at=executed_at,updated_by=author,updated_at=executed_at,
 submitted_by=CASE WHEN status<>'BOZZA' THEN author END,submitted_at=CASE WHEN status<>'BOZZA' THEN executed_at END WHERE created_by IS NULL;
CREATE INDEX IF NOT EXISTS coll_pat_latest_inspection ON coll_pat.inspections(project_id,manhole_id,executed_at DESC,id DESC) WHERE status='COMPLETO' AND NOT cancelled;
CREATE INDEX IF NOT EXISTS coll_pat_inspection_state ON coll_pat.inspections(project_id,status,updated_at DESC);
CREATE INDEX IF NOT EXISTS coll_pat_catalog_members ON coll_pat.catalog USING gin((data->'collectors')) WHERE kind IN ('point','segment');
CREATE INDEX IF NOT EXISTS coll_pat_catalog_active ON coll_pat.catalog(project_id,kind,((data->>'archived')::boolean));

CREATE TABLE IF NOT EXISTS coll_pat.inspection_photos (
 project_id uuid NOT NULL, id uuid NOT NULL, inspection_id uuid NOT NULL,
 storage_path text NOT NULL UNIQUE, created_by uuid NOT NULL REFERENCES auth.users(id), created_at timestamptz NOT NULL DEFAULT now(),
 uploaded boolean NOT NULL DEFAULT false, archived_at timestamptz,
 PRIMARY KEY(project_id,id), FOREIGN KEY(project_id,inspection_id) REFERENCES coll_pat.inspections(project_id,id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS coll_pat_photos_inspection ON coll_pat.inspection_photos(project_id,inspection_id) WHERE archived_at IS NULL;
ALTER TABLE coll_pat.inspection_photos ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON coll_pat.inspection_photos FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION coll_pat.catalog_lifecycle() RETURNS trigger LANGUAGE plpgsql SET search_path='' AS $$
BEGIN
 NEW.data:=NEW.data||jsonb_build_object('created_at',coalesce(OLD.data->'created_at',NEW.data->'created_at',to_jsonb(now())),'updated_at',now());
 IF NEW.kind='collector' THEN
   IF NEW.data->>'display_color' IS NULL THEN NEW.data:=NEW.data||jsonb_build_object('display_color','#176D73'); END IF;
   IF NEW.data->>'display_color' !~ '^#[0-9A-Fa-f]{6}$' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid display color'; END IF;
   IF coalesce((OLD.data->>'archived')::boolean,false) OR coalesce((NEW.data->>'archived')::boolean,false) THEN
     NEW.data:=NEW.data||jsonb_build_object('archived',true,'archived_at',coalesce(OLD.data->'archived_at',to_jsonb(now())),'archived_by',coalesce(OLD.data->'archived_by',to_jsonb(NEW.updated_by)));
   END IF;
 END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS coll_pat_lifecycle ON coll_pat.catalog;
CREATE TRIGGER coll_pat_lifecycle BEFORE INSERT OR UPDATE OF data ON coll_pat.catalog FOR EACH ROW EXECUTE FUNCTION coll_pat.catalog_lifecycle();

CREATE OR REPLACE FUNCTION coll_pat.active_catalog(p_project uuid,c coll_pat.catalog) RETURNS boolean LANGUAGE sql STABLE SET search_path='' AS $$
 SELECT CASE WHEN c.kind='collector' THEN NOT coalesce((c.data->>'archived')::boolean,false)
 ELSE NOT coalesce((c.data->>'archived')::boolean,false) AND EXISTS(SELECT 1 FROM coll_pat.catalog parent WHERE parent.project_id=p_project AND parent.kind='collector' AND c.data->'collectors' ? parent.id::text AND NOT coalesce((parent.data->>'archived')::boolean,false)) END
$$;
CREATE OR REPLACE FUNCTION public.coll_pat_catalog(p_project uuid,p_offset integer DEFAULT 0,p_revision bigint DEFAULT NULL) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE u uuid:=coll_pat.require_member(p_project);rev bigint;result jsonb;total integer;tombstones jsonb;
BEGIN
 IF p_offset<0 OR p_offset>100000 THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid catalog offset'; END IF;
 SELECT catalog_revision INTO rev FROM coll_pat.projects WHERE id=p_project FOR SHARE;
 IF p_revision IS NOT NULL AND p_revision<>rev THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Catalog changed while paging'; END IF;
 SELECT count(*) INTO total FROM coll_pat.catalog c WHERE project_id=p_project AND coll_pat.active_catalog(p_project,c);
 SELECT coalesce(jsonb_agg(jsonb_build_object('kind',kind,'data',data)),'[]') INTO result FROM
 (SELECT kind,data FROM coll_pat.catalog c WHERE project_id=p_project AND coll_pat.active_catalog(p_project,c) ORDER BY id LIMIT 200 OFFSET p_offset) q;
 SELECT coalesce(jsonb_agg(jsonb_build_object('kind',kind,'data',data)),'[]') INTO tombstones FROM coll_pat.catalog c
 WHERE project_id=p_project AND kind='collector' AND coalesce((data->>'archived')::boolean,false) AND p_offset=0;
 RETURN jsonb_build_object('items',result,'archived',tombstones,'revision',rev,'has_more',p_offset+jsonb_array_length(result)<total,'total',total);
END $$;

CREATE OR REPLACE FUNCTION coll_pat.inspection_json(i coll_pat.inspections) RETURNS jsonb LANGUAGE sql STABLE SET search_path='' AS $$
 SELECT to_jsonb(i)||jsonb_build_object('corrections',(SELECT coalesce(jsonb_agg(to_jsonb(c)),'[]') FROM coll_pat.cancellations c WHERE c.project_id=i.project_id AND c.inspection_id=i.id),
 'photos',(SELECT coalesce(jsonb_agg(to_jsonb(p) ORDER BY p.created_at,p.id),'[]') FROM coll_pat.inspection_photos p WHERE p.project_id=i.project_id AND p.inspection_id=i.id AND p.archived_at IS NULL),
 'execution_day',(i.executed_at AT TIME ZONE 'Europe/Rome')::date,'execution_year',extract(year FROM i.executed_at AT TIME ZONE 'Europe/Rome')::integer,
 'semester',CASE WHEN extract(month FROM i.executed_at AT TIME ZONE 'Europe/Rome')<=6 THEN 1 ELSE 2 END)
$$;
CREATE OR REPLACE FUNCTION public.coll_pat_inspection(p_project uuid,p_id uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE u uuid:=coll_pat.require_member(p_project);result jsonb;
BEGIN SELECT coll_pat.inspection_json(i) INTO result FROM coll_pat.inspections i WHERE project_id=p_project AND id=p_id;
 IF result IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='Scheda non disponibile'; END IF;RETURN result;END $$;
CREATE OR REPLACE FUNCTION public.coll_pat_history(p_project uuid,p_offset integer DEFAULT 0) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE u uuid:=coll_pat.require_member(p_project);rows jsonb;
BEGIN
 IF p_offset<0 THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid offset'; END IF;
 SELECT coalesce(jsonb_agg(coll_pat.inspection_json(q)),'[]') INTO rows FROM (SELECT i.* FROM coll_pat.inspections i WHERE i.project_id=p_project ORDER BY i.executed_at DESC,i.id LIMIT 200 OFFSET p_offset) q;
 RETURN jsonb_build_object('items',rows,'has_more',jsonb_array_length(rows)=200);
END $$;
-- Summary endpoint: only latest valid inspections + drafts, with a stable cursor (UUID).
CREATE OR REPLACE FUNCTION public.coll_pat_semester_history(p_project uuid,p_semester text,p_offset integer DEFAULT 0) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE u uuid:=coll_pat.require_member(p_project);rows jsonb;start_at timestamptz;end_at timestamptz;
BEGIN
 IF p_semester !~ '^[0-9]{4}-S[12]$' OR p_offset<0 THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Semestre non valido'; END IF;
 start_at:=make_date(left(p_semester,4)::integer,CASE WHEN right(p_semester,1)='1' THEN 1 ELSE 7 END,1)::timestamp AT TIME ZONE 'Europe/Rome';
 end_at:=((start_at AT TIME ZONE 'Europe/Rome')+interval '6 months') AT TIME ZONE 'Europe/Rome';
 SELECT coalesce(jsonb_agg(coll_pat.inspection_json(q)),'[]') INTO rows FROM
 (SELECT i.* FROM coll_pat.inspections i WHERE project_id=p_project AND status<>'BOZZA' AND executed_at>=start_at AND executed_at<end_at ORDER BY executed_at DESC,id LIMIT 200 OFFSET p_offset) q;
 RETURN jsonb_build_object('items',rows,'has_more',jsonb_array_length(rows)=200);
END $$;

CREATE OR REPLACE FUNCTION public.coll_pat_inspection_summary(p_project uuid,p_after uuid DEFAULT NULL) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE u uuid:=coll_pat.require_member(p_project);rows jsonb;
BEGIN
 SELECT coalesce(jsonb_agg(coll_pat.inspection_json(i) ORDER BY i.id),'[]') INTO rows FROM coll_pat.inspections i JOIN
 (SELECT id FROM (SELECT DISTINCT ON (manhole_id) id,manhole_id FROM coll_pat.inspections WHERE project_id=p_project AND status='COMPLETO' AND NOT cancelled ORDER BY manhole_id,executed_at DESC,id DESC) l
 UNION SELECT id FROM coll_pat.inspections WHERE project_id=p_project AND status='BOZZA' AND NOT cancelled) selected ON selected.id=i.id
 WHERE i.project_id=p_project AND (p_after IS NULL OR i.id>p_after) AND i.id IN
 (SELECT id FROM coll_pat.inspections WHERE project_id=p_project AND (p_after IS NULL OR id>p_after) ORDER BY id LIMIT 200);
 -- Cursor advances across all rows, including those not selected, so sparse pages cannot skip records.
 RETURN jsonb_build_object('items',rows,'cancelled_ids',(SELECT coalesce(jsonb_agg(id),'[]') FROM (SELECT id,cancelled FROM coll_pat.inspections WHERE project_id=p_project AND (p_after IS NULL OR id>p_after) ORDER BY id LIMIT 200) t WHERE cancelled),'next',(SELECT max(id::text) FROM (SELECT id FROM coll_pat.inspections WHERE project_id=p_project AND (p_after IS NULL OR id>p_after) ORDER BY id LIMIT 200) q));
END $$;

-- Shared draft and submission validator is defined below; the original protocol remains for catalogue/audit.

CREATE OR REPLACE FUNCTION public.coll_pat_save_inspection(operation jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE project uuid:=(operation->>'project_id')::uuid;u uuid:=coll_pat.require_member(project);op uuid:=(operation->>'operation_id')::uuid;
 p jsonb:=operation->'payload';gen bigint;rec coll_pat.inspections%ROWTYPE;old coll_pat.receipts%ROWTYPE;result jsonb;
 asset jsonb;sheet jsonb;e jsonb;selected_event jsonb;evaluation jsonb:='{}';item jsonb;key text;next_rev bigint;creator uuid;created timestamptz;at timestamptz:=clock_timestamp();limits jsonb;d double precision;
BEGIN
 SELECT generation INTO gen FROM coll_pat.projects WHERE id=project FOR UPDATE;
 IF (operation->>'generation')::bigint IS DISTINCT FROM gen THEN RAISE SQLSTATE 'PT409' USING MESSAGE='RESET_OBSOLETE'; END IF;
 SELECT * INTO old FROM coll_pat.receipts WHERE operation_id=op;
 IF FOUND THEN IF old.author<>u OR old.project_id<>project OR old.operation<>operation THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Operation UUID reused'; END IF;RETURN old.receipt;END IF;
 IF (operation->>'payload_version')::integer IS DISTINCT FROM 2 OR octet_length(operation::text)>5*1024*1024 OR
    (p->>'project_id')::uuid IS DISTINCT FROM project OR (p->>'generation')::bigint IS DISTINCT FROM gen OR
    p->>'status' NOT IN ('BOZZA','COMPLETO','IMPEDITO') OR jsonb_typeof(p->'sheet') IS DISTINCT FROM 'object' OR jsonb_typeof(p->'events') IS DISTINCT FROM 'array' OR
    p->>'model' NOT IN ('ORDINARY','ASPHALT_EXTERNAL','ASSET_EXTERNAL') OR NOT p ?& ARRAY['id','status','model','started_at','manhole_id','expected_revision'] THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid inspection'; END IF;
 SELECT * INTO rec FROM coll_pat.inspections WHERE project_id=project AND id=(p->>'id')::uuid FOR UPDATE;
 IF FOUND THEN
   IF rec.status<>'BOZZA' OR rec.cancelled OR rec.revision IS DISTINCT FROM (p->>'expected_revision')::bigint OR rec.manhole_id<>(p->>'manhole_id')::uuid THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Bozza aggiornata da un altro operatore'; END IF;
   creator:=rec.created_by;created:=rec.created_at;next_rev:=rec.revision+1;
 ELSE
   IF (p->>'expected_revision')::bigint IS DISTINCT FROM 0 THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Bozza non più disponibile'; END IF;
   creator:=u;created:=at;next_rev:=1;
 END IF;
 SELECT c.data INTO asset FROM coll_pat.catalog c WHERE c.project_id=project AND c.id=(p->>'manhole_id')::uuid AND c.kind='point' AND coll_pat.active_catalog(project,c);
 IF asset IS NULL THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Manufatto non disponibile nel catalogo attivo'; END IF;
 IF jsonb_array_length(p->'events')>1000 OR (SELECT count(*)<>count(DISTINCT value->>'id') FROM jsonb_array_elements(p->'events')) THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid GPS events'; END IF;
 -- Identity checks also apply to drafts, before accepting evidence from another user.
 FOR e IN SELECT value FROM jsonb_array_elements(p->'events') LOOP
   IF (e->>'user_id')::uuid IS DISTINCT FROM u AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(coalesce(rec.original->'events','[]')) old_event WHERE old_event-'cancelled'=e-'cancelled') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='Cannot impersonate another operator'; END IF;
 END LOOP;
 IF p->>'status'<>'BOZZA' THEN
   IF (p->>'completed_at')::timestamptz<(p->>'started_at')::timestamptz OR (p->>'completed_at')::timestamptz>now()+interval '1 day' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid execution timestamp'; END IF;
   sheet:=p->'sheet';
   IF p->>'status'='IMPEDITO' THEN
     IF length(btrim(coalesce(sheet->>'impediment_reason','')))<1 OR coalesce((sheet->>'opened')::boolean,true) THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Impediment reason/no opening required'; END IF;
   ELSIF (sheet->>'accessible')::boolean IS DISTINCT FROM true OR (sheet->>'unsafe')::boolean IS DISTINCT FROM false THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Unsafe/inaccessible: record impediment';
   ELSIF p->>'model'='ORDINARY' THEN
     IF (sheet->>'opened')::boolean IS DISTINCT FROM true OR asset->>'asset_type'<>'MANHOLE' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Ordinary inspection requires declared opening of a manhole'; END IF;
     FOREACH key IN ARRAY ARRAY['cover','deposits','flow','walls','damage','closure','restored'] LOOP
       IF coalesce(sheet->>key,'') NOT IN ('REGOLARE','ANOMALO','NON_APPLICABILE') THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Incomplete internal observations'; END IF;
     END LOOP;
   ELSE
     IF (sheet->>'opened')::boolean IS DISTINCT FROM false OR nullif(sheet->>'no_open_reason','') IS NULL THEN RAISE SQLSTATE 'PT400' USING MESSAGE='External inspection cannot declare opening'; END IF;
   END IF;
   IF p->>'model'<>'ORDINARY' OR p->>'status'='IMPEDITO' THEN
     FOREACH key IN ARRAY ARRAY['deposits','flow','walls','damage'] LOOP
       IF sheet->>key IS DISTINCT FROM 'NON_OSSERVABILE' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Unobserved internal conditions must not be regular'; END IF;
     END LOOP;
   END IF;
   IF (EXISTS(SELECT 1 FROM jsonb_each_text(sheet) WHERE value='ANOMALO') OR coalesce((sheet->>'raise_needed')::boolean,false) OR coalesce((sheet->>'road_repair_needed')::boolean,false)) AND nullif(btrim(sheet->>'anomaly_note'),'') IS NULL THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Describe anomaly'; END IF;
   IF jsonb_typeof(p->'events') IS DISTINCT FROM 'array' OR jsonb_array_length(p->'events')>1000 THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid GPS events'; END IF;
   IF (SELECT count(*)<>count(DISTINCT value->>'id') FROM jsonb_array_elements(p->'events')) THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Duplicate GPS event'; END IF;
   FOR e IN SELECT value FROM jsonb_array_elements(p->'events') LOOP
     IF NOT e ?& ARRAY['id','acquired_at','requested_at','permission','rule_version'] OR e->>'id' IS NULL OR e->>'acquired_at' IS NULL OR e->>'permission' NOT IN ('PRECISE','APPROXIMATE','DENIED') THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Incomplete GPS event'; END IF;
     IF NOT EXISTS(SELECT 1 FROM coll_pat.memberships m WHERE m.project_id=project AND m.user_id=(e->>'user_id')::uuid) OR e->>'inspection_id' IS DISTINCT FROM p->>'id' OR e->>'manhole_id' IS DISTINCT FROM p->>'manhole_id' THEN RAISE SQLSTATE 'PT403' USING MESSAGE='GPS evidence identity mismatch'; END IF;
     IF (e->>'user_id')::uuid<>u AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(coalesce(rec.original->'events','[]')) old_event WHERE old_event-'cancelled'=e-'cancelled') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='Cannot impersonate another operator'; END IF;
     PERFORM (e->>'id')::uuid;PERFORM (e->>'acquired_at')::timestamptz;
     IF e ? 'cancelled' AND (NOT EXISTS(SELECT 1 FROM coll_pat.memberships m WHERE m.project_id=project AND m.user_id=(e->'cancelled'->>'author')::uuid) OR length(btrim(coalesce(e->'cancelled'->>'reason','')))<3) THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid draft evidence cancellation'; END IF;
   END LOOP;
   SELECT value INTO selected_event FROM jsonb_array_elements(p->'events') WHERE NOT value ? 'cancelled' ORDER BY (value->>'acquired_at')::timestamptz DESC,value->>'id' DESC LIMIT 1;
   IF selected_event IS NULL THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Record a GPS attempt, including failure'; END IF;
   evaluation:=coll_pat.gps(selected_event,asset,project);
   IF evaluation->>'state'<>'COMPATIBILE' AND nullif(btrim(sheet->>'exception_reason'),'') IS NULL THEN RAISE SQLSTATE 'PT400' USING MESSAGE='GPS exception reason required'; END IF;

   IF p ? 'cancelled' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Use cancellation action'; END IF;
   IF p->>'status'='COMPLETO' THEN
     limits:=selected_event->'applied_limits';
     d:=coll_pat.distance_m((selected_event->>'latitude')::double precision,(selected_event->>'longitude')::double precision,(asset->>'latitude')::double precision,(asset->>'longitude')::double precision);
     IF NOT coalesce((limits->>'max_accuracy_m')::double precision BETWEEN 1 AND 50 AND (limits->>'radius_m')::double precision BETWEEN 1 AND 100 AND
       (selected_event->>'accuracy_m')::double precision BETWEEN 0 AND (limits->>'max_accuracy_m')::double precision AND
       d BETWEEN 0 AND (limits->>'radius_m')::double precision AND (selected_event->>'age_s')::double precision BETWEEN 0 AND 10 AND
       (selected_event->>'latitude')::double precision BETWEEN -90 AND 90 AND (selected_event->>'longitude')::double precision BETWEEN -180 AND 180 AND
       selected_event->>'permission'='PRECISE' AND NOT coalesce((selected_event->>'mock')::boolean,false) AND nullif(selected_event->>'error','') IS NULL,false)
     THEN RAISE SQLSTATE 'PT400' USING MESSAGE='GPS non affidabile: accuratezza o distanza fuori soglia'; END IF;
     evaluation:=evaluation||jsonb_build_object('gps_reliability','RELIABLE','distance_m',d,'applied_limits',limits);
   END IF;
 END IF;
 p:=(p-'expected_revision'-'server_revision'-'local_edit')||jsonb_build_object('created_by',creator,'created_at',created,'updated_by',u,'updated_at',at,'revision',next_rev,
 'submitted_by',CASE WHEN p->>'status'<>'BOZZA' THEN u END,'submitted_at',CASE WHEN p->>'status'<>'BOZZA' THEN at END,'user_id',creator,'periodic_control',p->>'status'='COMPLETO');
 INSERT INTO coll_pat.inspections(project_id,id,author,generation,manhole_id,executed_at,model,status,periodic_control,original,server_gps,cancelled,created_by,created_at,updated_by,updated_at,submitted_by,submitted_at,revision)
 VALUES(project,(p->>'id')::uuid,creator,gen,(p->>'manhole_id')::uuid,coalesce((p->>'completed_at')::timestamptz,(p->>'started_at')::timestamptz),p->>'model',p->>'status',p->>'status'='COMPLETO',p,evaluation,p ? 'cancelled',creator,created,u,at,CASE WHEN p->>'status'<>'BOZZA' THEN u END,CASE WHEN p->>'status'<>'BOZZA' THEN at END,next_rev)
 ON CONFLICT(project_id,id) DO UPDATE SET original=excluded.original,executed_at=excluded.executed_at,model=excluded.model,status=excluded.status,periodic_control=excluded.periodic_control,server_gps=excluded.server_gps,cancelled=excluded.cancelled,updated_by=u,updated_at=at,submitted_by=excluded.submitted_by,submitted_at=excluded.submitted_at,revision=next_rev;
 IF jsonb_typeof(p->'photos') IS DISTINCT FROM 'array' OR jsonb_array_length(p->'photos')>100 THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid photos'; END IF;
 UPDATE coll_pat.inspection_photos SET archived_at=at WHERE project_id=project AND inspection_id=(p->>'id')::uuid AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(p->'photos') photo WHERE (photo->>'id')::uuid=inspection_photos.id);
 FOR item IN SELECT value FROM jsonb_array_elements(p->'photos') LOOP
   IF item ?| ARRAY['localUri','remoteUrl','base64','data'] THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Private local photo data not accepted'; END IF;
   INSERT INTO coll_pat.inspection_photos(project_id,id,inspection_id,storage_path,created_by,created_at)
   VALUES(project,(item->>'id')::uuid,(p->>'id')::uuid,project::text||'/'||(p->>'id')||'/'||(item->>'id')||'.jpg',u,coalesce((item->>'created_at')::timestamptz,at))
   ON CONFLICT(project_id,id) DO NOTHING;
   IF NOT EXISTS(SELECT 1 FROM coll_pat.inspection_photos WHERE project_id=project AND id=(item->>'id')::uuid AND inspection_id=(p->>'id')::uuid AND archived_at IS NULL) THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Photo identifier already used'; END IF;
 END LOOP;
 UPDATE coll_pat.projects SET inspection_revision=inspection_revision+1 WHERE id=project;
 result:=jsonb_build_object('operation_id',op,'project_id',project,'generation',gen,'received_at',at,'revision',next_rev,'server_gps',evaluation,
 'created_by',creator,'created_at',created,'updated_by',u,'updated_at',at,'submitted_by',CASE WHEN p->>'status'<>'BOZZA' THEN u END,'submitted_at',CASE WHEN p->>'status'<>'BOZZA' THEN at END);
 INSERT INTO coll_pat.receipts(operation_id,project_id,author,generation,operation,receipt) VALUES(op,project,u,gen,operation,result);
 RETURN result;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range OR not_null_violation OR check_violation THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Malformed inspection payload';
 WHEN unique_violation THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Identifier already used';
END $$;

-- Retain the tested catalogue/cancellation implementation privately. New submissions use revision checks.
DO $$ BEGIN
 IF to_regprocedure('coll_pat.apply_v013(jsonb)') IS NULL THEN
   ALTER FUNCTION public.coll_pat_apply(jsonb) SET SCHEMA coll_pat;
   ALTER FUNCTION coll_pat.coll_pat_apply(jsonb) RENAME TO apply_v013;
 END IF;
END $$;
CREATE OR REPLACE FUNCTION coll_pat.cancel_v014(operation jsonb) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE project uuid:=(operation->>'project_id')::uuid;u uuid:=coll_pat.require_member(project);op uuid:=(operation->>'operation_id')::uuid;p jsonb:=operation->'payload';rec coll_pat.inspections%ROWTYPE;gen bigint;old coll_pat.receipts%ROWTYPE;original jsonb;result jsonb;
BEGIN
 SELECT generation INTO gen FROM coll_pat.projects WHERE id=project FOR UPDATE;
 IF (operation->>'generation')::bigint IS DISTINCT FROM gen THEN RAISE SQLSTATE 'PT409' USING MESSAGE='RESET_OBSOLETE';END IF;
 SELECT * INTO old FROM coll_pat.receipts WHERE operation_id=op;
 IF FOUND THEN IF old.author<>u OR old.operation<>operation THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Operation UUID reused';END IF;RETURN old.receipt;END IF;
 SELECT * INTO rec FROM coll_pat.inspections WHERE project_id=project AND id=(p->>'inspection_id')::uuid;
 IF rec.submitted_by IS DISTINCT FROM u OR (p->>'author')::uuid IS DISTINCT FROM u THEN RAISE SQLSTATE 'PT403' USING MESSAGE='Cancellation denied';END IF;
 IF p->>'event_id' IS NULL THEN original:=rec.original;UPDATE coll_pat.inspections SET cancelled=true,updated_by=u,updated_at=now() WHERE project_id=project AND id=rec.id;
 ELSE SELECT value INTO original FROM jsonb_array_elements(rec.original->'events') WHERE value->>'id'=p->>'event_id';
 IF original IS NULL OR original ? 'cancelled' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Unknown event';END IF;END IF;
 INSERT INTO coll_pat.cancellations(project_id,id,inspection_id,event_id,author,at,reason,original) VALUES(project,(p->>'id')::uuid,rec.id,(p->>'event_id')::uuid,u,(p->>'at')::timestamptz,p->>'reason',original);
 UPDATE coll_pat.projects SET inspection_revision=inspection_revision+1 WHERE id=project;
 result:=jsonb_build_object('operation_id',op,'project_id',project,'generation',gen,'received_at',now());
 INSERT INTO coll_pat.receipts VALUES(op,project,u,gen,operation,result);RETURN result;
END $$;
CREATE OR REPLACE FUNCTION public.coll_pat_apply(operation jsonb) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM coll_pat.require_member((operation->>'project_id')::uuid);
 IF operation->>'kind'='inspection' THEN RAISE SQLSTATE 'PT426' USING MESSAGE='Aggiornare il protocollo ispezioni'; END IF;
 IF operation->>'kind'='cancel' THEN
   -- Submitting an inspection does not change its creator, but the submitter can correct it.
   IF EXISTS(SELECT 1 FROM coll_pat.inspections i WHERE i.project_id=(operation->>'project_id')::uuid AND i.id=(operation->'payload'->>'inspection_id')::uuid AND i.submitted_by=auth.uid() AND i.author<>auth.uid()) THEN
     RETURN coll_pat.cancel_v014(operation);
   END IF;
 END IF;
 RETURN coll_pat.apply_v013(operation);
END $$;

REVOKE ALL ON ALL FUNCTIONS IN SCHEMA coll_pat FROM PUBLIC,anon,authenticated;
DO $$ DECLARE f record;BEGIN
 FOR f IN SELECT p.oid::regprocedure signature FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname LIKE 'coll_pat_%' LOOP
   EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC,anon,authenticated',f.signature);
   EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO authenticated',f.signature);
 END LOOP;
END $$;
GRANT EXECUTE ON FUNCTION public.coll_pat_version() TO anon;
NOTIFY pgrst,'reload schema';
COMMIT;
