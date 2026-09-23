-- v0.21 GPS method, no-record reason and restricted object fields. Existing events unchanged.
BEGIN;
CREATE OR REPLACE FUNCTION coll_pat.validate_gps_v021(e jsonb,asset jsonb,project uuid) RETURNS jsonb LANGUAGE plpgsql STABLE SET search_path='' AS $$
DECLARE samples jsonb:=e->'samples';n integer;first_ns bigint;last_ns bigint;start_ns bigint:=(e->>'started_elapsed_ns')::bigint;end_ns bigint:=(e->>'ended_elapsed_ns')::bigint;
 a double precision;x double precision;y double precision;z double precision;lat double precision;lon double precision;disp double precision;result jsonb;BEGIN
 IF e->>'method' IS DISTINCT FROM 'SPHERICAL_MEAN_MEAN_ACCURACY_V2' OR jsonb_typeof(samples) IS DISTINCT FROM 'array' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Metodo GPS non verificabile';END IF;
 n:=jsonb_array_length(samples);
 IF NOT coalesce(n BETWEEN 3 AND 100 AND (e->>'sample_count')::integer=n AND (e->>'stabilization_ms')::integer=3000 AND end_ns-start_ns BETWEEN 8000000000 AND 31000000000 AND (e->>'sampling_ms')::bigint=(end_ns-start_ns-3000000000)/1000000 AND (e->>'duration_ms')::bigint=(end_ns-start_ns)/1000000 AND (e->'applied_limits'->>'max_accuracy_m')::double precision IN (20,40,60,80,100,120,140,150) AND (e->'applied_limits'->>'radius_m')::double precision IN (5,10,15,20,25,30),false) THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Acquisizione GPS incompleta o soglie non ammesse';END IF;
 IF EXISTS(SELECT 1 FROM jsonb_array_elements(samples) s WHERE NOT coalesce((s->>'elapsed_ns')::bigint BETWEEN start_ns+3000000000 AND end_ns AND (s->>'latitude')::double precision BETWEEN -90 AND 90 AND (s->>'longitude')::double precision BETWEEN -180 AND 180 AND (s->>'accuracy_m')::double precision BETWEEN 0 AND 40000000 AND (s->>'mock')::boolean=false,false)) THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Campione GPS non valido o in stabilizzazione';END IF;
 IF (SELECT count(DISTINCT s->>'elapsed_ns') FROM jsonb_array_elements(samples) s)<>n THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Campioni GPS duplicati';END IF;
 SELECT min((s->>'elapsed_ns')::bigint),max((s->>'elapsed_ns')::bigint),avg((s->>'accuracy_m')::double precision),sum(cos(radians((s->>'latitude')::double precision))*cos(radians((s->>'longitude')::double precision))),sum(cos(radians((s->>'latitude')::double precision))*sin(radians((s->>'longitude')::double precision))),sum(sin(radians((s->>'latitude')::double precision))) INTO first_ns,last_ns,a,x,y,z FROM jsonb_array_elements(samples) s;
 lat:=degrees(atan2(z,sqrt(x*x+y*y)));lon:=degrees(atan2(y,x));
 SELECT max(coll_pat.distance_m(lat,lon,(s->>'latitude')::double precision,(s->>'longitude')::double precision)) INTO disp FROM jsonb_array_elements(samples) s;
 IF NOT coalesce(last_ns-first_ns>=2000000000 AND end_ns-last_ns BETWEEN 0 AND 2000000000 AND (e->>'last_sample_elapsed_ns')::bigint=last_ns AND (e->>'sample_span_ms')::bigint=(last_ns-first_ns)/1000000 AND abs((e->>'age_s')::double precision-(end_ns-last_ns)/1000000000.0)<0.001 AND abs((e->>'accuracy_m')::double precision-a)<0.001 AND abs((e->>'dispersion_m')::double precision-disp)<0.1 AND coll_pat.distance_m(lat,lon,(e->>'latitude')::double precision,(e->>'longitude')::double precision)<0.1 AND a<=(e->'applied_limits'->>'max_accuracy_m')::double precision AND e->>'permission'='PRECISE' AND (e->>'mock')::boolean=false AND nullif(e->>'error','') IS NULL AND (e->>'acquisition_ended_at')::timestamptz>=(e->>'acquisition_started_at')::timestamptz,false) THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Media GPS incoerente o accuratezza insufficiente';END IF;
 result:=coll_pat.gps_v015(e,asset,project);
 IF e->>'match_outcome' IS DISTINCT FROM 'VERIFIED' OR result->>'state'<>'COMPATIBILE' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Corrispondenza GPS non verificata';END IF;
 RETURN result||jsonb_build_object('method',e->>'method','applied_limits',e->'applied_limits','gps_reliability','RELIABLE');
END $$;
CREATE OR REPLACE FUNCTION public.coll_pat_save_inspection(operation jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE project uuid:=(operation->>'project_id')::uuid;u uuid:=coll_pat.require_member(project);op uuid:=(operation->>'operation_id')::uuid;
 p jsonb:=operation->'payload';gen bigint;rec coll_pat.inspections%ROWTYPE;old coll_pat.receipts%ROWTYPE;result jsonb;
 asset jsonb;sheet jsonb;e jsonb;selected_event jsonb;evaluation jsonb:='{}';item jsonb;key text;next_rev bigint;creator uuid;created timestamptz;at timestamptz:=clock_timestamp();limits jsonb;d double precision;
BEGIN
 SELECT generation INTO gen FROM coll_pat.projects WHERE id=project FOR UPDATE;
 IF (operation->>'generation')::bigint IS DISTINCT FROM gen THEN RAISE SQLSTATE 'PT409' USING MESSAGE='RESET_OBSOLETE'; END IF;
 result:=coll_pat.retired_receipt(operation);IF result IS NOT NULL THEN RETURN result;END IF;
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
 IF (coll_pat.version_parts(operation->>'app_version')>=coll_pat.version_parts('0.21') AND rec.id IS NULL OR rec.original->>'gps_contract'='2') AND p->>'gps_contract' IS DISTINCT FROM '2' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Contratto GPS v2 richiesto';END IF;
 SELECT c.data INTO asset FROM coll_pat.catalog c WHERE c.project_id=project AND c.id=(p->>'manhole_id')::uuid AND c.kind='point' AND coll_pat.active_catalog(project,c);
 IF asset IS NULL THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Manufatto non disponibile nel catalogo attivo'; END IF;
 IF p->>'gps_contract'='2' THEN
   IF (SELECT count(*) FROM jsonb_array_elements(p->'events') ev WHERE NOT ev ? 'cancelled')>1 THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Una sola localizzazione attiva';END IF;
   IF p->'gps_state'->>'status'='NOT_RECORDED_WITH_REASON' THEN
     IF jsonb_array_length(p->'events')<>0 OR nullif(btrim(p->'gps_state'->>'reason'),'') IS NULL OR length(p->'gps_state'->>'reason')>1000 OR p->'gps_state' ?| ARRAY['latitude','longitude','accuracy_m'] OR nullif(p->'gps_state'->>'attempt_id','') IS NULL THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Mancato rilievo GPS non valido';END IF;
     IF (p->'gps_state'->>'author')::uuid IS DISTINCT FROM u AND p->'gps_state' IS DISTINCT FROM rec.original->'gps_state' THEN RAISE SQLSTATE 'PT403' USING MESSAGE='Autore motivazione GPS non valido';END IF;
     IF nullif(p->'gps_state'->>'confirmed_at','') IS NULL OR nullif(p->'gps_state'->>'attempted_at','') IS NULL OR (p->'gps_state'->>'confirmed_at')::timestamptz<(p->'gps_state'->>'attempted_at')::timestamptz THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Tentativo GPS e conferma richiesti';END IF;
     PERFORM (p->'gps_state'->>'attempt_id')::uuid;
   ELSIF p->'gps_state'->>'status' NOT IN ('ATTEMPTED','RECORDED') AND p->>'status'='COMPLETO' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Stato GPS richiesto';END IF;
 END IF;
 IF jsonb_array_length(p->'events')>1000 OR (SELECT count(*)<>count(DISTINCT value->>'id') FROM jsonb_array_elements(p->'events')) THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid GPS events'; END IF;
 -- Identity checks also apply to drafts, before accepting evidence from another user.
 FOR e IN SELECT value FROM jsonb_array_elements(p->'events') LOOP
   IF p->>'gps_contract'='2' AND NOT e ? 'cancelled' AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(coalesce(rec.original->'events','[]')) previous WHERE previous=e) THEN
     IF e->>'method' IS DISTINCT FROM 'SPHERICAL_MEAN_MEAN_ACCURACY_V2' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Metodo GPS v2 richiesto';END IF;
     IF e->>'inspection_id' IS DISTINCT FROM p->>'id' OR e->>'manhole_id' IS DISTINCT FROM p->>'manhole_id' THEN RAISE SQLSTATE 'PT403' USING MESSAGE='GPS evidence identity mismatch';END IF;
     PERFORM coll_pat.validate_gps_v021(e,asset,project);
   END IF;
   IF e->>'method'='SPHERICAL_MEAN_MAX_ACCURACY_V1' AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(coalesce(rec.original->'events','[]')) old_event WHERE old_event-'cancelled'=e-'cancelled') THEN PERFORM coll_pat.validate_gps_v015(e,asset,project); END IF;
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
   IF selected_event IS NULL AND p->>'status'<>'IMPEDITO' AND NOT coalesce(p->>'gps_contract'='2' AND p->'gps_state'->>'status'='NOT_RECORDED_WITH_REASON',false) THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Record a valid GPS acquisition'; END IF;
   evaluation:=CASE WHEN p->>'gps_contract'='2' AND p->'gps_state'->>'status'='NOT_RECORDED_WITH_REASON' THEN jsonb_build_object('state','NOT_RECORDED_WITH_REASON','gps_reliability','NOT_RECORDED_WITH_REASON') WHEN selected_event->>'method'='SPHERICAL_MEAN_MEAN_ACCURACY_V2' THEN coll_pat.validate_gps_v021(selected_event,asset,project) WHEN selected_event->>'method'='SPHERICAL_MEAN_MAX_ACCURACY_V1' THEN coll_pat.validate_gps_v015(selected_event,asset,project) ELSE coll_pat.gps(selected_event,asset,project) END;
   IF selected_event IS NOT NULL AND evaluation->>'state'<>'COMPATIBILE' AND nullif(btrim(sheet->>'exception_reason'),'') IS NULL THEN RAISE SQLSTATE 'PT400' USING MESSAGE='GPS exception reason required'; END IF;

   IF p ? 'cancelled' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Use cancellation action'; END IF;
   IF p->>'status'='COMPLETO' AND selected_event IS NOT NULL THEN
     limits:=selected_event->'applied_limits';
     d:=coll_pat.distance_m((selected_event->>'latitude')::double precision,(selected_event->>'longitude')::double precision,(asset->>'latitude')::double precision,(asset->>'longitude')::double precision);
     IF NOT coalesce(((selected_event->>'method'='SPHERICAL_MEAN_MEAN_ACCURACY_V2' AND (limits->>'max_accuracy_m')::double precision IN (20,40,60,80,100,120,140,150) AND (limits->>'radius_m')::double precision IN (5,10,15,20,25,30)) OR (selected_event->>'method'<>'SPHERICAL_MEAN_MEAN_ACCURACY_V2' AND (limits->>'max_accuracy_m')::double precision BETWEEN 1 AND 50 AND (limits->>'radius_m')::double precision BETWEEN 1 AND 100)) AND
       (selected_event->>'accuracy_m')::double precision BETWEEN 0 AND (limits->>'max_accuracy_m')::double precision AND
       (d BETWEEN 0 AND (limits->>'radius_m')::double precision OR (selected_event->>'method'='SPHERICAL_MEAN_MAX_ACCURACY_V1' AND selected_event->>'match_outcome'='EXCEPTION' AND nullif(btrim(selected_event->>'exception_reason'),'') IS NOT NULL)) AND (selected_event->>'age_s')::double precision BETWEEN 0 AND 10 AND
       (selected_event->>'latitude')::double precision BETWEEN -90 AND 90 AND (selected_event->>'longitude')::double precision BETWEEN -180 AND 180 AND
       selected_event->>'permission'='PRECISE' AND NOT coalesce((selected_event->>'mock')::boolean,false) AND nullif(selected_event->>'error','') IS NULL,false)
     THEN RAISE SQLSTATE 'PT400' USING MESSAGE='GPS non affidabile: accuratezza o distanza fuori soglia'; END IF;
     evaluation:=evaluation||jsonb_build_object('gps_reliability',CASE WHEN selected_event->>'match_outcome'='EXCEPTION' THEN 'MATCH_EXCEPTION' ELSE 'RELIABLE' END,'distance_m',d,'applied_limits',limits);
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


CREATE OR REPLACE FUNCTION public.coll_pat_patch_object(operation jsonb) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE project uuid:=(operation->>'project_id')::uuid;u uuid:=coll_pat.require_member(project);p jsonb:=operation->'payload';c coll_pat.catalog%ROWTYPE;result jsonb;gen bigint;k text;BEGIN
 SELECT generation INTO gen FROM coll_pat.projects WHERE id=project FOR UPDATE;
 IF gen IS DISTINCT FROM (operation->>'generation')::bigint OR (operation->>'payload_version')::int IS DISTINCT FROM 2 OR operation->>'kind' IS DISTINCT FROM 'object_patch' THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Contratto aggiornamento non valido';END IF;
 result:=coll_pat.retired_receipt(operation);IF result IS NOT NULL THEN RETURN result;END IF;
 SELECT * INTO c FROM coll_pat.catalog WHERE project_id=project AND id=(p->>'id')::uuid FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Oggetto eliminato o assente';END IF;
 IF jsonb_typeof(p->'changes') IS DISTINCT FROM 'object' OR jsonb_typeof(p->'expected') IS DISTINCT FROM 'object' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Modifiche non valide';END IF;
 FOR k IN SELECT jsonb_object_keys(p->'changes') LOOP
  IF NOT (c.kind='point' AND k='under_asphalt' AND jsonb_typeof(p->'changes'->k)='boolean') THEN
    PERFORM coll_pat.require_member(project,true);
    IF NOT (c.kind='collector' AND k IN ('display_color','display_width','symbol') OR c.kind='point' AND k='symbol') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='Proprieta non autorizzata';END IF;
  END IF;
  IF coalesce(c.data->k,'null'::jsonb) IS DISTINCT FROM coalesce(p->'expected'->k,'null'::jsonb) THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Proprieta modificata da altro dispositivo';END IF;
  IF k='display_color' AND p->'changes'->k<>'null'::jsonb AND p->'changes'->>k !~ '^#[0-9a-fA-F]{6}$' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Colore non valido';END IF;
  IF k='symbol' AND p->'changes'->k<>'null'::jsonb AND p->'changes'->>k NOT IN ('CIRCLE','SQUARE','DIAMOND','HEXAGON','RING','CHAMBER','DOT') THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Forma non valida';END IF;
  IF k='display_width' AND p->'changes'->k<>'null'::jsonb AND (p->'changes'->>k)::numeric NOT IN (1,2,3,4,5,6,7,8,9,10) THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Spessore non valido';END IF;
 END LOOP;
 UPDATE coll_pat.catalog SET data=data||(p->'changes'),updated_by=u,updated_at=now() WHERE project_id=project AND id=c.id;
 UPDATE coll_pat.projects SET catalog_revision=catalog_revision+1 WHERE id=project;
 result:=jsonb_build_object('operation_id',operation->>'operation_id','project_id',project,'generation',gen,'server_at',now());
 INSERT INTO coll_pat.retired_operations VALUES((operation->>'operation_id')::uuid,project,u,gen,coll_pat.operation_hash(operation),result);RETURN result;
END $$;
REVOKE ALL ON FUNCTION coll_pat.validate_gps_v021(jsonb,jsonb,uuid) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION public.coll_pat_patch_object(jsonb) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.coll_pat_patch_object(jsonb) TO authenticated;
CREATE OR REPLACE FUNCTION coll_pat.catalog_lifecycle() RETURNS trigger LANGUAGE plpgsql SET search_path='' AS $$
BEGIN
 NEW.data:=NEW.data||jsonb_build_object('created_at',coalesce(OLD.data->'created_at',NEW.data->'created_at',to_jsonb(now())),'updated_at',now());
 IF NEW.kind='collector' THEN
   -- NULL means inherit the device's general display preference; keep historical overrides.
   IF NEW.data->>'display_color' IS NOT NULL AND NEW.data->>'display_color' !~ '^#[0-9A-Fa-f]{6}$' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Invalid display color'; END IF;
   IF coalesce((OLD.data->>'archived')::boolean,false) OR coalesce((NEW.data->>'archived')::boolean,false) THEN
     NEW.data:=NEW.data||jsonb_build_object('archived',true,'archived_at',coalesce(OLD.data->'archived_at',to_jsonb(now())),'archived_by',coalesce(OLD.data->'archived_by',to_jsonb(NEW.updated_by)));
   END IF;
 END IF;RETURN NEW;
END $$;
NOTIFY pgrst,'reload schema';
COMMIT;
