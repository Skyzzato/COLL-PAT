-- v0.15 additive GPS contract. Existing event JSON and historical records are retained.
-- No Room/table columns needed: original/events already preserve the full evidence.
-- Does not change latest/minimum version: announce latest only after publishing the APK.
BEGIN;
CREATE OR REPLACE FUNCTION coll_pat.gps_v015(p_event jsonb,p_point jsonb,p_project uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SET search_path='' AS $$
DECLARE d double precision; a double precision; age double precision; g double precision; ambiguous boolean; reasons jsonb:='[]'; state text; radius double precision:=(p_event->'applied_limits'->>'radius_m')::double precision; accuracy_limit double precision:=(p_event->'applied_limits'->>'max_accuracy_m')::double precision;
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
 SELECT count(*)>1 INTO ambiguous FROM coll_pat.catalog c WHERE c.project_id=p_project AND c.kind='point' AND coll_pat.active_catalog(p_project,c) AND
 coll_pat.distance_m((p_event->>'latitude')::double precision,(p_event->>'longitude')::double precision,(c.data->>'latitude')::double precision,(c.data->>'longitude')::double precision)<=radius;
 IF p_event->>'permission' IS DISTINCT FROM 'PRECISE' THEN reasons:=reasons||jsonb_build_array('permesso non preciso'); END IF;
 IF a IS NULL OR a<0 OR a>accuracy_limit OR a='NaN'::double precision THEN reasons:=reasons||jsonb_build_array('accuratezza insufficiente'); END IF;
 IF g IS NULL THEN reasons:=reasons||jsonb_build_array('qualità cartografica da verificare'); ELSIF g<0 OR g>10 THEN reasons:=reasons||jsonb_build_array('incertezza cartografica elevata'); END IF;
 IF (p_event->>'mock')::boolean THEN reasons:=reasons||jsonb_build_array('posizione simulata segnalata'); END IF;
 IF ambiguous THEN reasons:=reasons||jsonb_build_array('manufatti vicini: selezione da verificare'); END IF;
 IF jsonb_array_length(reasons)>0 THEN state:='INCERTA';
 ELSIF d+a+g<=radius THEN state:='COMPATIBILE';
 ELSIF d-a-g>radius THEN state:='NON_COMPATIBILE'; ELSE state:='INCERTA'; END IF;
 RETURN jsonb_build_object('state',state,'distance_m',d,'reasons',reasons,'rule_version','gps-1','ambiguous',ambiguous);
END $$;

CREATE OR REPLACE FUNCTION coll_pat.validate_gps_v015(e jsonb,asset jsonb,project uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SET search_path='' AS $$
DECLARE limits jsonb:=e->'applied_limits';evaluation jsonb;
BEGIN
 IF NOT coalesce((limits->>'max_accuracy_m')::double precision BETWEEN 1 AND 50 AND
   (limits->>'radius_m')::double precision BETWEEN 1 AND 100 AND
   (e->>'accuracy_m')::double precision BETWEEN 0 AND (limits->>'max_accuracy_m')::double precision AND
   (e->>'latitude')::double precision BETWEEN -90 AND 90 AND (e->>'longitude')::double precision BETWEEN -180 AND 180 AND
   (e->>'age_s')::double precision BETWEEN 0 AND 2 AND e->>'permission'='PRECISE' AND
   (e->>'mock')::boolean=false AND nullif(e->>'error','') IS NULL,false)
 THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Accuratezza GPS insufficiente o misura non utilizzabile: nessuna deroga'; END IF;
 IF NOT coalesce((e->>'sample_count')::integer BETWEEN 3 AND 100 AND (e->>'duration_ms')::bigint BETWEEN 5000 AND 10000 AND
   (e->>'sample_span_ms')::bigint BETWEEN 2000 AND 5000 AND
   (e->>'dispersion_m')::double precision BETWEEN 0 AND 40000000 AND
   (e->>'ended_elapsed_ns')::bigint-(e->>'started_elapsed_ns')::bigint >= 5000000000 AND
   (e->>'ended_elapsed_ns')::bigint-(e->>'last_sample_elapsed_ns')::bigint BETWEEN 0 AND 2000000000 AND
   (e->>'acquisition_started_at')::timestamptz <= (e->>'acquisition_ended_at')::timestamptz,false)
 THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Acquisizione GPS incompleta'; END IF;
 evaluation:=coll_pat.gps_v015(e,asset,project);
 IF e->>'match_outcome'='EXCEPTION' THEN
   IF nullif(btrim(e->>'exception_reason'),'') IS NULL THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Motivazione Eccezione GPS obbligatoria'; END IF;
 ELSIF e->>'match_outcome' IS DISTINCT FROM 'VERIFIED' OR evaluation->>'state'<>'COMPATIBILE' THEN
   RAISE SQLSTATE 'PT400' USING MESSAGE='Corrispondenza GPS non verificata: confermare una eccezione motivata';
 END IF;
 RETURN evaluation||jsonb_build_object('match_outcome',e->>'match_outcome','exception_reason',e->>'exception_reason','method',e->>'method','applied_limits',limits);
END $$;

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
   IF selected_event IS NULL AND p->>'status'<>'IMPEDITO' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Record a valid GPS acquisition'; END IF;
   evaluation:=CASE WHEN selected_event->>'method'='SPHERICAL_MEAN_MAX_ACCURACY_V1' THEN coll_pat.validate_gps_v015(selected_event,asset,project) ELSE coll_pat.gps(selected_event,asset,project) END;
   IF selected_event IS NOT NULL AND evaluation->>'state'<>'COMPATIBILE' AND nullif(btrim(sheet->>'exception_reason'),'') IS NULL THEN RAISE SQLSTATE 'PT400' USING MESSAGE='GPS exception reason required'; END IF;

   IF p ? 'cancelled' THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Use cancellation action'; END IF;
   IF p->>'status'='COMPLETO' THEN
     limits:=selected_event->'applied_limits';
     d:=coll_pat.distance_m((selected_event->>'latitude')::double precision,(selected_event->>'longitude')::double precision,(asset->>'latitude')::double precision,(asset->>'longitude')::double precision);
     IF NOT coalesce((limits->>'max_accuracy_m')::double precision BETWEEN 1 AND 50 AND (limits->>'radius_m')::double precision BETWEEN 1 AND 100 AND
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

REVOKE ALL ON FUNCTION coll_pat.gps_v015(jsonb,jsonb,uuid),coll_pat.validate_gps_v015(jsonb,jsonb,uuid) FROM PUBLIC,anon,authenticated;
COMMIT;
