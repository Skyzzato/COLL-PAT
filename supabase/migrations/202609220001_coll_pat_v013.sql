-- COLL-PAT v0.13: independent Auth/RPC protocol, private tables, immutable operations.
-- Apply as database owner. Never executed by Android. No destructive reset here.
BEGIN;
CREATE SCHEMA coll_pat;
REVOKE ALL ON SCHEMA coll_pat FROM PUBLIC, anon, authenticated;
CREATE TABLE coll_pat.projects (
 id uuid PRIMARY KEY, name text NOT NULL, development boolean NOT NULL DEFAULT false,
 generation bigint NOT NULL DEFAULT 0 CHECK(generation>=0), catalog_revision bigint NOT NULL DEFAULT 0,
 inspection_revision bigint NOT NULL DEFAULT 0
);
CREATE TABLE coll_pat.memberships (
 project_id uuid NOT NULL REFERENCES coll_pat.projects(id), user_id uuid NOT NULL REFERENCES auth.users(id),
 role text NOT NULL CHECK(role IN ('inspector','admin')), PRIMARY KEY(project_id,user_id)
);
CREATE TABLE coll_pat.legacy_identity_links (
 legacy_user_id text PRIMARY KEY, auth_user_id uuid NOT NULL REFERENCES auth.users(id),
 linked_by uuid NOT NULL REFERENCES auth.users(id), linked_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE coll_pat.catalog (
 project_id uuid NOT NULL REFERENCES coll_pat.projects(id), id uuid NOT NULL,
 kind text NOT NULL CHECK(kind IN ('collector','point','segment')), data jsonb NOT NULL CHECK(jsonb_typeof(data)='object'),
 updated_by uuid NOT NULL REFERENCES auth.users(id), updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(project_id,id), CHECK((data->>'id')::uuid=id)
);
CREATE UNIQUE INDEX coll_pat_collector_code ON coll_pat.catalog(project_id,lower(data->>'code')) WHERE kind='collector';
CREATE UNIQUE INDEX coll_pat_source_key ON coll_pat.catalog(project_id,kind,(data->>'source_identity')) WHERE data ? 'source_identity';
CREATE TABLE coll_pat.inspections (
 project_id uuid NOT NULL REFERENCES coll_pat.projects(id), id uuid NOT NULL, author uuid NOT NULL REFERENCES auth.users(id),
 generation bigint NOT NULL, manhole_id uuid NOT NULL, executed_at timestamptz NOT NULL,
 model text NOT NULL CHECK(model IN ('ORDINARY','ASPHALT_EXTERNAL','ASSET_EXTERNAL')),
 status text NOT NULL CHECK(status IN ('COMPLETO','IMPEDITO')), periodic_control boolean NOT NULL,
 original jsonb NOT NULL, server_gps jsonb NOT NULL, cancelled boolean NOT NULL DEFAULT false,
 PRIMARY KEY(project_id,id), FOREIGN KEY(project_id,manhole_id) REFERENCES coll_pat.catalog(project_id,id),
 CHECK(periodic_control=(status='COMPLETO'))
);
CREATE INDEX coll_pat_execution ON coll_pat.inspections(project_id,executed_at);
-- Each event is preserved in original, including draft cancellations. Post-registration corrections are separate.
CREATE TABLE coll_pat.cancellations (
 project_id uuid NOT NULL, id uuid NOT NULL, inspection_id uuid NOT NULL, event_id uuid,
 author uuid NOT NULL REFERENCES auth.users(id), at timestamptz NOT NULL, reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 3 AND 500),
 original jsonb NOT NULL, PRIMARY KEY(project_id,id),
 FOREIGN KEY(project_id,inspection_id) REFERENCES coll_pat.inspections(project_id,id)
);
CREATE UNIQUE INDEX coll_pat_cancel_event ON coll_pat.cancellations(project_id,inspection_id,event_id) WHERE event_id IS NOT NULL;
CREATE UNIQUE INDEX coll_pat_cancel_inspection ON coll_pat.cancellations(project_id,inspection_id) WHERE event_id IS NULL;
CREATE TABLE coll_pat.receipts (
 operation_id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES coll_pat.projects(id), author uuid NOT NULL REFERENCES auth.users(id),
 generation bigint NOT NULL, operation jsonb NOT NULL, receipt jsonb NOT NULL
);
CREATE TABLE coll_pat.imports (
 project_id uuid NOT NULL REFERENCES coll_pat.projects(id), id uuid NOT NULL, author uuid NOT NULL REFERENCES auth.users(id),
 provenance jsonb NOT NULL, at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(project_id,id)
);
CREATE TABLE coll_pat.catalog_chunks (
 operation_id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES coll_pat.projects(id), batch_id uuid NOT NULL,
 author uuid NOT NULL REFERENCES auth.users(id), generation bigint NOT NULL, ordinal integer NOT NULL CHECK(ordinal BETWEEN 0 AND 9999),
 items jsonb NOT NULL, UNIQUE(project_id,batch_id,ordinal)
);
CREATE TABLE coll_pat.reset_log (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), project_id uuid NOT NULL REFERENCES coll_pat.projects(id), author uuid NOT NULL,
 at timestamptz NOT NULL DEFAULT now(), previous_generation bigint NOT NULL, generation bigint NOT NULL,
 inspection_count integer NOT NULL, UNIQUE(project_id,generation)
);
CREATE TABLE coll_pat.backup_tokens (
 token uuid PRIMARY KEY DEFAULT gen_random_uuid(), project_id uuid NOT NULL REFERENCES coll_pat.projects(id), author uuid NOT NULL,
 generation bigint NOT NULL, inspection_revision bigint NOT NULL, at timestamptz NOT NULL DEFAULT now()
);

DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['projects','memberships','legacy_identity_links','catalog','inspections','cancellations','receipts','imports','catalog_chunks','reset_log','backup_tokens'] LOOP
   EXECUTE format('ALTER TABLE coll_pat.%I ENABLE ROW LEVEL SECURITY',t);
   EXECUTE format('REVOKE ALL ON coll_pat.%I FROM PUBLIC, anon, authenticated',t);
 END LOOP;
END $$;
-- No client table DML grants. All entry points check Auth and project membership, including read RPCs.
CREATE FUNCTION coll_pat.require_member(p_project uuid,p_admin boolean DEFAULT false) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE u uuid:=auth.uid(); r text;
BEGIN
 IF u IS NULL THEN RAISE SQLSTATE 'PT401' USING MESSAGE='Supabase Auth required'; END IF;
 SELECT role INTO r FROM coll_pat.memberships WHERE project_id=p_project AND user_id=u;
 IF r IS NULL OR (p_admin AND r<>'admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='Project permission denied'; END IF;
 RETURN u;
END $$;

CREATE FUNCTION public.coll_pat_status(p_project uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE u uuid:=coll_pat.require_member(p_project); result jsonb;
BEGIN
 SELECT jsonb_build_object('project_id',p.id,'generation',p.generation,'revision',p.catalog_revision,'role',m.role,
 'development',p.development,'inspection_count',(SELECT count(*) FROM coll_pat.inspections i WHERE i.project_id=p.id))
 INTO result FROM coll_pat.projects p JOIN coll_pat.memberships m ON m.project_id=p.id AND m.user_id=u WHERE p.id=p_project;
 RETURN result;
END $$;

CREATE FUNCTION public.coll_pat_catalog(p_project uuid,p_offset integer DEFAULT 0,p_revision bigint DEFAULT NULL) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE u uuid:=coll_pat.require_member(p_project); rev bigint; result jsonb; total integer;
BEGIN
 IF p_offset<0 OR p_offset>100000 THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid catalog offset'; END IF;
 SELECT catalog_revision INTO rev FROM coll_pat.projects WHERE id=p_project FOR SHARE;
 IF p_revision IS NOT NULL AND p_revision<>rev THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Catalog changed while paging: restart download'; END IF;
 SELECT count(*) INTO total FROM coll_pat.catalog WHERE project_id=p_project;
 SELECT coalesce(jsonb_agg(jsonb_build_object('kind',kind,'data',data)),'[]') INTO result
 FROM (SELECT kind,data FROM coll_pat.catalog WHERE project_id=p_project ORDER BY id LIMIT 200 OFFSET p_offset) q;
 RETURN jsonb_build_object('items',result,'revision',rev,'has_more',p_offset+jsonb_array_length(result)<total,'total',total);
END $$;

CREATE FUNCTION coll_pat.distance_m(lat1 double precision,lon1 double precision,lat2 double precision,lon2 double precision) RETURNS double precision
LANGUAGE sql IMMUTABLE STRICT SET search_path='' AS $$
 SELECT 12742017.6 * asin(sqrt(least(1.0,greatest(0.0,power(sin(radians(lat2-lat1)/2),2)+cos(radians(lat1))*cos(radians(lat2))*power(sin(radians(lon2-lon1)/2),2)))))
$$;

CREATE FUNCTION coll_pat.gps(p_event jsonb,p_point jsonb,p_project uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SET search_path='' AS $$
DECLARE d double precision; a double precision; age double precision; g double precision; ambiguous boolean; reasons jsonb:='[]'; state text;
BEGIN
 IF p_event IS NULL THEN RETURN jsonb_build_object('state','NON_DISPONIBILE','reasons',jsonb_build_array('misura assente o troppo vecchia')); END IF;
 IF p_event->>'rule_version' IS DISTINCT FROM 'gps-1' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Unsupported GPS rule'; END IF;
 IF (p_event->>'latitude')::double precision BETWEEN -90 AND 90 AND (p_event->>'longitude')::double precision BETWEEN -180 AND 180 THEN
   d:=coll_pat.distance_m((p_event->>'latitude')::double precision,(p_event->>'longitude')::double precision,(p_point->>'latitude')::double precision,(p_point->>'longitude')::double precision);
 END IF;
 a:=(p_event->>'accuracy_m')::double precision; age:=(p_event->>'age_s')::double precision; g:=(p_point->>'uncertainty_m')::double precision;
 IF d IS NULL OR nullif(p_event->>'error','') IS NOT NULL OR age IS NULL OR age<0 OR age>10 THEN
   RETURN jsonb_build_object('state','NON_DISPONIBILE','distance_m',d,'reasons',jsonb_build_array(coalesce(nullif(p_event->>'error',''),'misura assente o troppo vecchia')),'rule_version','gps-1');
 END IF;
 SELECT count(*)>1 INTO ambiguous FROM coll_pat.catalog c WHERE c.project_id=p_project AND c.kind='point' AND
 coll_pat.distance_m((p_event->>'latitude')::double precision,(p_event->>'longitude')::double precision,(c.data->>'latitude')::double precision,(c.data->>'longitude')::double precision)<=20;
 IF p_event->>'permission' IS DISTINCT FROM 'PRECISE' THEN reasons:=reasons||jsonb_build_array('permesso non preciso'); END IF;
 IF a IS NULL OR a<0 OR a>15 OR a='NaN'::double precision THEN reasons:=reasons||jsonb_build_array('accuratezza insufficiente'); END IF;
 IF g IS NULL THEN reasons:=reasons||jsonb_build_array('qualità cartografica da verificare'); ELSIF g<0 OR g>10 THEN reasons:=reasons||jsonb_build_array('incertezza cartografica elevata'); END IF;
 IF (p_event->>'mock')::boolean THEN reasons:=reasons||jsonb_build_array('posizione simulata segnalata'); END IF;
 IF ambiguous THEN reasons:=reasons||jsonb_build_array('manufatti vicini: selezione da verificare'); END IF;
 IF jsonb_array_length(reasons)>0 THEN state:='INCERTA';
 ELSIF d+a+g<=20 THEN state:='COMPATIBILE';
 ELSIF d-a-g>20 THEN state:='NON_COMPATIBILE'; ELSE state:='INCERTA'; END IF;
 RETURN jsonb_build_object('state',state,'distance_m',d,'reasons',reasons,'rule_version','gps-1','ambiguous',ambiguous);
END $$;

CREATE FUNCTION coll_pat.validate_catalog(p_project uuid,p_kind text,p_data jsonb) RETURNS void
LANGUAGE plpgsql SET search_path='' AS $$
DECLARE cid text; line jsonb; coord jsonb; k text; n numeric;
BEGIN
 PERFORM (p_data->>'id')::uuid;
 IF p_kind='collector' THEN
   IF coalesce(length(btrim(p_data->>'code')),0) NOT BETWEEN 1 AND 200 OR coalesce(length(btrim(p_data->>'description')),0) NOT BETWEEN 1 AND 1000
      OR NOT p_data ?& ARRAY['code','description','type','visits_h1','visits_h2','hours_km_visit','length_m','length_source','length_complete']
      OR coalesce(p_data->>'type','') NOT IN ('CV','CZI','CR','BOE''','opere accessorie') THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid collector'; END IF;
   FOREACH k IN ARRAY ARRAY['visits_h1','visits_h2','hours_km_visit'] LOOP
     IF jsonb_typeof(p_data->k) IS DISTINCT FROM 'number' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Numeric catalogue field required'; END IF;
     n:=(p_data->>k)::numeric;
     IF n<0 OR n>1000000 OR (k LIKE 'visits_%' AND n<>trunc(n)) THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid planning number'; END IF;
   END LOOP;
   IF coalesce(p_data->>'length_source','') NOT IN ('UNAVAILABLE','MEASURED','ESTIMATED','DECLARED') OR
      ((p_data->>'length_m' IS NULL) IS DISTINCT FROM (p_data->>'length_source'='UNAVAILABLE')) OR
      (p_data->>'length_m')::numeric<0 THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid length and provenance'; END IF;
 ELSIF p_kind IN ('point','segment') THEN
   IF jsonb_typeof(p_data->'collectors') IS DISTINCT FROM 'array' OR jsonb_array_length(p_data->'collectors')=0 THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Collector membership required'; END IF;
   FOR cid IN SELECT jsonb_array_elements_text(p_data->'collectors') LOOP
     IF NOT EXISTS(SELECT 1 FROM coll_pat.catalog WHERE project_id=p_project AND id=cid::uuid AND kind='collector') THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Unknown collector'; END IF;
   END LOOP;
   IF p_kind='point' THEN
     IF coalesce(length(btrim(p_data->>'code')),0)=0 OR coalesce(p_data->>'asset_type','')='' OR
        NOT coalesce((p_data->>'latitude')::numeric BETWEEN -90 AND 90 AND (p_data->>'longitude')::numeric BETWEEN -180 AND 180,false) THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid point'; END IF;
   ELSE
     IF p_data->'geometry'->>'type' NOT IN ('LineString','MultiLineString') OR p_data->'geometry' IS NULL THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Line geometry required'; END IF;
     FOR line IN SELECT value FROM jsonb_array_elements(CASE WHEN p_data->'geometry'->>'type'='LineString' THEN jsonb_build_array(p_data->'geometry'->'coordinates') ELSE p_data->'geometry'->'coordinates' END) LOOP
       IF jsonb_array_length(line)<2 THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Line needs two vertices'; END IF;
       FOR coord IN SELECT value FROM jsonb_array_elements(line) LOOP
         IF NOT coalesce((coord->>0)::numeric BETWEEN -180 AND 180 AND (coord->>1)::numeric BETWEEN -90 AND 90,false) THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid line coordinates'; END IF;
       END LOOP;
     END LOOP;
     FOREACH k IN ARRAY ARRAY['from_id','to_id'] LOOP
       IF NOT EXISTS(SELECT 1 FROM coll_pat.catalog WHERE project_id=p_project AND id=(p_data->>k)::uuid AND kind='point') THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Unknown line endpoint'; END IF;
     END LOOP;
   END IF;
 ELSE RAISE SQLSTATE 'PT400' USING MESSAGE='Unsupported catalog kind'; END IF;
END $$;

CREATE FUNCTION public.coll_pat_apply(operation jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE project uuid:=(operation->>'project_id')::uuid; u uuid:=coll_pat.require_member(project); op uuid:=(operation->>'operation_id')::uuid;
 gen bigint; kind text:=operation->>'kind'; p jsonb:=operation->'payload'; old coll_pat.receipts%ROWTYPE; result jsonb;
 item jsonb; payload_data jsonb; asset jsonb; e jsonb; selected_event jsonb; evaluation jsonb; sheet jsonb; key text; rec coll_pat.inspections%ROWTYPE; original jsonb;
BEGIN
 IF (operation->>'payload_version')::integer IS DISTINCT FROM 2 OR jsonb_typeof(p) IS DISTINCT FROM 'object' OR octet_length(operation::text)>5*1024*1024 THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Unsupported or oversized protocol'; END IF;
 -- ALL writes and resets share this persistent row lock. Includes concurrent workers and lost responses.
 SELECT generation INTO gen FROM coll_pat.projects WHERE id=project FOR UPDATE;
 IF (operation->>'generation')::bigint IS DISTINCT FROM gen THEN RAISE SQLSTATE 'PT409' USING MESSAGE='RESET_OBSOLETE: origin generation differs; explicit recovery required'; END IF;
 SELECT * INTO old FROM coll_pat.receipts WHERE operation_id=op;
 IF FOUND THEN
   IF old.author<>u OR old.project_id<>project OR old.operation<>operation THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Operation UUID reused with different identity/content'; END IF;
   RETURN old.receipt;
 END IF;
 IF kind='catalog_chunk' THEN
   PERFORM coll_pat.require_member(project,true);
   IF jsonb_typeof(p->'items') IS DISTINCT FROM 'array' OR jsonb_array_length(p->'items')>10000 THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid private staging batch'; END IF;
   INSERT INTO coll_pat.catalog_chunks(operation_id,project_id,batch_id,author,generation,ordinal,items)
   VALUES(op,project,(p->>'batch_id')::uuid,u,gen,(p->>'index')::integer,p->'items');
 ELSIF kind='catalog' THEN
   PERFORM coll_pat.require_member(project,true);
   IF p ? 'batch_id' THEN
     IF jsonb_typeof(p->'chunks') IS DISTINCT FROM 'array' OR jsonb_array_length(p->'chunks')=0 OR jsonb_array_length(p->'chunks')>10000 THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Explicit staging receipt list required'; END IF;
     IF EXISTS(SELECT 1 FROM jsonb_array_elements_text(p->'chunks') WITH ORDINALITY x(id,n)
        LEFT JOIN coll_pat.catalog_chunks c ON c.operation_id=x.id::uuid AND c.project_id=project AND c.author=u AND c.generation=gen AND c.batch_id=(p->>'batch_id')::uuid AND c.ordinal=x.n-1 WHERE c.operation_id IS NULL)
       THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Staging incomplete: no catalogue published'; END IF;
     IF (SELECT sum(octet_length(c.items::text)) FROM coll_pat.catalog_chunks c WHERE c.project_id=project AND c.batch_id=(p->>'batch_id')::uuid)>45*1024*1024 THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Staged catalogue too large'; END IF;
     SELECT jsonb_agg(v.value ORDER BY c.ordinal,v.n) INTO payload_data FROM coll_pat.catalog_chunks c,
       LATERAL jsonb_array_elements(c.items) WITH ORDINALITY v(value,n)
       WHERE c.project_id=project AND c.batch_id=(p->>'batch_id')::uuid AND c.operation_id::text IN (SELECT jsonb_array_elements_text(p->'chunks'));
     p:=p||jsonb_build_object('items',payload_data);
   END IF;
   IF jsonb_typeof(p->'items') IS DISTINCT FROM 'array' OR jsonb_array_length(p->'items')>10000 THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid catalog batch'; END IF;
   IF (SELECT count(*)<>count(DISTINCT value->'data'->>'id') FROM jsonb_array_elements(p->'items')) THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Duplicate UUID in batch'; END IF;
   FOR item IN SELECT value FROM jsonb_array_elements(p->'items') ORDER BY CASE value->>'kind' WHEN 'collector' THEN 0 WHEN 'point' THEN 1 ELSE 2 END LOOP
     payload_data:=item->'data';PERFORM coll_pat.validate_catalog(project,item->>'kind',payload_data);
     IF EXISTS(SELECT 1 FROM coll_pat.catalog c WHERE c.project_id=project AND c.id=(payload_data->>'id')::uuid AND c.kind<>item->>'kind') THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Immutable asset kind'; END IF;
     IF item->>'kind'='point' THEN
       SELECT c.data INTO asset FROM coll_pat.catalog c WHERE c.project_id=project AND c.id=(payload_data->>'id')::uuid;
       IF asset IS NOT NULL THEN payload_data:=jsonb_set(payload_data,'{collectors}',(SELECT jsonb_agg(DISTINCT value) FROM jsonb_array_elements((asset->'collectors')||(payload_data->'collectors')))); END IF;
     END IF;
     -- Imports cannot overwrite an operator's declared length. Manual collector edits may explicitly change it.
     IF p->'provenance' IS NOT NULL AND p->'provenance'<>'null'::jsonb AND item->>'kind'='collector' THEN
       SELECT c.data INTO asset FROM coll_pat.catalog c WHERE c.project_id=project AND c.id=(payload_data->>'id')::uuid;
       IF asset->>'length_source'='DECLARED' THEN payload_data:=payload_data||jsonb_build_object('length_m',asset->'length_m','length_source','DECLARED','length_complete',asset->'length_complete'); END IF;
     END IF;
     INSERT INTO coll_pat.catalog(project_id,id,kind,data,updated_by) VALUES(project,(payload_data->>'id')::uuid,item->>'kind',payload_data,u)
     ON CONFLICT(project_id,id) DO UPDATE SET data=excluded.data,updated_by=u,updated_at=now();
   END LOOP;
   IF p->'provenance' IS NOT NULL AND p->'provenance'<>'null'::jsonb THEN
     INSERT INTO coll_pat.imports(project_id,id,author,provenance) VALUES(project,(p->'provenance'->>'id')::uuid,u,p->'provenance');
   END IF;
   UPDATE coll_pat.projects SET catalog_revision=catalog_revision+1 WHERE id=project;
   PERFORM coll_pat.refresh_lengths(project);
   IF p ? 'batch_id' THEN DELETE FROM coll_pat.catalog_chunks WHERE project_id=project AND batch_id=(p->>'batch_id')::uuid; END IF;
 ELSIF kind='inspection' THEN
   IF (p->>'user_id')::uuid IS DISTINCT FROM u OR (p->>'project_id')::uuid IS DISTINCT FROM project OR (p->>'generation')::bigint IS DISTINCT FROM gen OR (p->>'payload_version')::integer IS DISTINCT FROM 2 THEN RAISE SQLSTATE 'PT403' USING MESSAGE='Inspection identity does not match Auth/project'; END IF;
   IF p->>'status' NOT IN ('COMPLETO','IMPEDITO') OR p->>'model' NOT IN ('ORDINARY','ASPHALT_EXTERNAL','ASSET_EXTERNAL') OR NOT p ?& ARRAY['status','model','id','manhole_id','started_at','completed_at','sheet','events','periodic_control'] THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid inspection model/status'; END IF;
   IF (p->>'periodic_control')::boolean IS DISTINCT FROM (p->>'status'='COMPLETO') THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Incorrect periodic qualification'; END IF;
   SELECT c.data INTO asset FROM coll_pat.catalog c WHERE c.project_id=project AND c.id=(p->>'manhole_id')::uuid AND c.kind='point';
   IF asset IS NULL THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Publish asset catalogue before inspection'; END IF;
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
     IF (e->>'user_id')::uuid IS DISTINCT FROM u OR e->>'inspection_id' IS DISTINCT FROM p->>'id' OR e->>'manhole_id' IS DISTINCT FROM p->>'manhole_id' THEN RAISE SQLSTATE 'PT403' USING MESSAGE='GPS evidence identity mismatch'; END IF;
     PERFORM (e->>'id')::uuid;PERFORM (e->>'acquired_at')::timestamptz;
     IF e ? 'cancelled' AND ((e->'cancelled'->>'author')::uuid IS DISTINCT FROM u OR length(btrim(coalesce(e->'cancelled'->>'reason','')))<3) THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid draft evidence cancellation'; END IF;
   END LOOP;
   SELECT value INTO selected_event FROM jsonb_array_elements(p->'events') WHERE NOT value ? 'cancelled' ORDER BY (value->>'acquired_at')::timestamptz DESC,value->>'id' DESC LIMIT 1;
   IF selected_event IS NULL THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Record a GPS attempt, including failure'; END IF;
   evaluation:=coll_pat.gps(selected_event,asset,project);
   IF evaluation->>'state'<>'COMPATIBILE' AND nullif(btrim(sheet->>'exception_reason'),'') IS NULL THEN RAISE SQLSTATE 'PT400' USING MESSAGE='GPS exception reason required'; END IF;
   IF p ? 'cancelled' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Use separate audited cancellation operation'; END IF;
   IF jsonb_typeof(p->'photos') IS DISTINCT FROM 'array' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Photo metadata array required'; END IF;
   FOR item IN SELECT value FROM jsonb_array_elements(p->'photos') LOOP
     IF item ?| ARRAY['localUri','remoteUrl','base64','data'] OR item->>'upload' IS DISTINCT FROM 'SIMULATED_LOCAL_ONLY' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Only local photo metadata accepted'; END IF;
   END LOOP;
   INSERT INTO coll_pat.inspections(project_id,id,author,generation,manhole_id,executed_at,model,status,periodic_control,original,server_gps)
   VALUES(project,(p->>'id')::uuid,u,gen,(p->>'manhole_id')::uuid,(p->>'completed_at')::timestamptz,p->>'model',p->>'status',(p->>'periodic_control')::boolean,p,evaluation)
   ON CONFLICT(project_id,id) DO NOTHING;
   IF NOT FOUND THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Inspection UUID already registered; retry original operation'; END IF;
   UPDATE coll_pat.projects SET inspection_revision=inspection_revision+1 WHERE id=project;
 ELSIF kind='cancel' THEN
   SELECT * INTO rec FROM coll_pat.inspections WHERE project_id=project AND id=(p->>'inspection_id')::uuid;
   IF NOT FOUND THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Creation must be received before cancellation'; END IF;
   IF rec.author<>u THEN PERFORM coll_pat.require_member(project,true); END IF;
   IF (p->>'author')::uuid IS DISTINCT FROM u THEN RAISE SQLSTATE 'PT403' USING MESSAGE='Cancellation author mismatch'; END IF;
   IF p->>'event_id' IS NULL THEN
     original:=rec.original;UPDATE coll_pat.inspections SET cancelled=true WHERE project_id=project AND id=rec.id;
   ELSE
     SELECT value INTO original FROM jsonb_array_elements(rec.original->'events') WHERE value->>'id'=p->>'event_id';
     IF original IS NULL OR original ? 'cancelled' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Unknown or already cancelled event'; END IF;
   END IF;
   INSERT INTO coll_pat.cancellations(project_id,id,inspection_id,event_id,author,at,reason,original)
   VALUES(project,(p->>'id')::uuid,rec.id,(p->>'event_id')::uuid,u,(p->>'at')::timestamptz,p->>'reason',original);
   UPDATE coll_pat.projects SET inspection_revision=inspection_revision+1 WHERE id=project;
 ELSE RAISE SQLSTATE 'PT400' USING MESSAGE='Unsupported operation kind'; END IF;
 result:=jsonb_build_object('operation_id',op,'project_id',project,'generation',gen,'received_at',clock_timestamp(),'server_gps',evaluation);
 INSERT INTO coll_pat.receipts(operation_id,project_id,author,generation,operation,receipt) VALUES(op,project,u,gen,operation,result)
 ON CONFLICT(operation_id) DO NOTHING;
 -- Global UUID collision across projects is still a conflict; no successful unreceipted write.
 IF NOT FOUND THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Operation UUID already belongs to another project'; END IF;
 RETURN result;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range OR not_null_violation OR check_violation THEN
 RAISE SQLSTATE 'PT400' USING MESSAGE='Malformed structured payload';
 WHEN unique_violation THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Catalogue/source/audit identity conflict';
END $$;

CREATE FUNCTION public.coll_pat_history(p_project uuid,p_offset integer DEFAULT 0) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE u uuid:=coll_pat.require_member(p_project); rows jsonb;
BEGIN
 IF p_offset<0 THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid offset'; END IF;
 SELECT coalesce(jsonb_agg(to_jsonb(q)),'[]') INTO rows FROM (
 SELECT i.*, (SELECT coalesce(jsonb_agg(to_jsonb(c)),'[]') FROM coll_pat.cancellations c WHERE c.project_id=i.project_id AND c.inspection_id=i.id) AS corrections,
 (i.executed_at AT TIME ZONE 'Europe/Rome')::date AS execution_day,
 extract(year FROM i.executed_at AT TIME ZONE 'Europe/Rome')::integer AS execution_year,
 CASE WHEN extract(month FROM i.executed_at AT TIME ZONE 'Europe/Rome')<=6 THEN 1 ELSE 2 END AS semester
 FROM coll_pat.inspections i WHERE i.project_id=p_project ORDER BY i.executed_at DESC,i.id LIMIT 200 OFFSET p_offset) q;
 RETURN jsonb_build_object('items',rows,'has_more',jsonb_array_length(rows)=200);
END $$;

CREATE FUNCTION public.coll_pat_backup(p_project uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE u uuid:=coll_pat.require_member(p_project,true); p coll_pat.projects%ROWTYPE; token uuid;
BEGIN
 SELECT * INTO p FROM coll_pat.projects WHERE id=p_project FOR UPDATE;
 INSERT INTO coll_pat.backup_tokens(project_id,author,generation,inspection_revision) VALUES(p_project,u,p.generation,p.inspection_revision) RETURNING backup_tokens.token INTO token;
 RETURN jsonb_build_object('project_id',p_project,'generation',p.generation,'backup_token',token,
 'inspections',(SELECT coalesce(jsonb_agg(to_jsonb(i)),'[]') FROM coll_pat.inspections i WHERE project_id=p_project),
 'cancellations',(SELECT coalesce(jsonb_agg(to_jsonb(c)),'[]') FROM coll_pat.cancellations c WHERE project_id=p_project),
 'receipts',(SELECT coalesce(jsonb_agg(to_jsonb(r)),'[]') FROM coll_pat.receipts r WHERE project_id=p_project AND operation->>'kind' NOT IN ('catalog','catalog_chunk')));
END $$;

CREATE FUNCTION public.coll_pat_reset(p_project uuid,p_generation bigint,p_confirmation text,p_backup_token uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE u uuid:=coll_pat.require_member(p_project,true); p coll_pat.projects%ROWTYPE; n integer;
BEGIN
 SELECT * INTO p FROM coll_pat.projects WHERE id=p_project FOR UPDATE;
 IF p_confirmation IS DISTINCT FROM 'AZZERA' OR p_generation IS DISTINCT FROM p.generation THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Reset confirmation/generation mismatch'; END IF;
 IF NOT EXISTS(SELECT 1 FROM coll_pat.backup_tokens b WHERE b.token=p_backup_token AND b.project_id=p_project AND b.author=u AND b.generation=p.generation AND b.inspection_revision=p.inspection_revision AND b.at>now()-interval '15 minutes') THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Fresh backup required; inspections changed or backup expired'; END IF;
 SELECT count(*) INTO n FROM coll_pat.inspections WHERE project_id=p_project;
 DELETE FROM coll_pat.cancellations WHERE project_id=p_project;
 DELETE FROM coll_pat.receipts WHERE project_id=p_project AND operation->>'kind' NOT IN ('catalog','catalog_chunk');
 DELETE FROM coll_pat.inspections WHERE project_id=p_project;
 DELETE FROM coll_pat.backup_tokens WHERE project_id=p_project;
 UPDATE coll_pat.projects SET generation=generation+1,inspection_revision=inspection_revision+1 WHERE id=p_project;
 INSERT INTO coll_pat.reset_log(project_id,author,previous_generation,generation,inspection_count) VALUES(p_project,u,p.generation,p.generation+1,n);
 RETURN jsonb_build_object('project_id',p_project,'generation',p.generation+1,'count',n);
END $$;

-- Block legacy runtime writers from republishing inspections outside the generation protocol.
-- Keep legacy users, passwords and sessions private, with no automatic Auth migration.
DO $$ DECLARE t text; BEGIN
 IF EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='collettori') THEN
   EXECUTE 'REVOKE ALL ON SCHEMA collettori FROM PUBLIC, anon, authenticated';
   EXECUTE 'REVOKE ALL ON ALL TABLES IN SCHEMA collettori FROM PUBLIC, anon, authenticated';
   IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='collettori_backend') THEN
     FOR t IN SELECT tablename FROM pg_tables WHERE schemaname='collettori' AND tablename IN ('inspections','revisions','location_events','anomalies','inspection_revisions','inspection_events','gps_events','receipts','operations','sync_operations','evidence_events') LOOP
       EXECUTE format('REVOKE INSERT, UPDATE, DELETE ON collettori.%I FROM collettori_backend',t);
     END LOOP;
   END IF;
 END IF;
END $$;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA coll_pat FROM PUBLIC,anon,authenticated;
REVOKE ALL ON ALL TABLES IN SCHEMA coll_pat FROM PUBLIC,anon,authenticated;
DO $$ DECLARE f record; BEGIN
 FOR f IN SELECT p.oid::regprocedure AS signature FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname LIKE 'coll_pat_%' LOOP
   EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC,anon,authenticated',f.signature);
   EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO authenticated',f.signature);
 END LOOP;
END $$;
NOTIFY pgrst,'reload schema';
COMMIT;
