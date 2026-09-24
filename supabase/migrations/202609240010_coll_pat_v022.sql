-- v0.22: additive, repeatable. No catalogue seed, account recreation or permission grants to pending users.
BEGIN;
ALTER TABLE coll_pat.memberships DROP CONSTRAINT IF EXISTS memberships_role_check;
ALTER TABLE coll_pat.memberships ADD CONSTRAINT memberships_role_check CHECK(role IN ('viewer','inspector','admin'));

CREATE OR REPLACE FUNCTION coll_pat.require_operator(p_project uuid) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE u uuid:=coll_pat.require_member(p_project); BEGIN
 IF NOT EXISTS(SELECT 1 FROM coll_pat.memberships WHERE project_id=p_project AND user_id=u AND role IN ('inspector','admin')) THEN
  RAISE SQLSTATE 'PT403' USING MESSAGE='Operazione vietata al visualizzatore';END IF;
 RETURN u;
END $$;

-- Freeze the deployed implementations in the private schema once; public wrappers check CURRENT membership,
-- including receipt retries. A project lock serializes permission revocation and operational writes.
DO $$ DECLARE item text;definition text;BEGIN
 FOREACH item IN ARRAY ARRAY['coll_pat_apply','coll_pat_save_inspection','coll_pat_patch_object','coll_pat_delete_permanent'] LOOP
  IF to_regprocedure('coll_pat.'||item||'_v021(jsonb)') IS NULL THEN
   SELECT pg_get_functiondef(to_regprocedure('public.'||item||'(jsonb)')) INTO STRICT definition;
   EXECUTE replace(definition,'public.'||item||'(', 'coll_pat.'||item||'_v021(');
  END IF;
  EXECUTE format('CREATE OR REPLACE FUNCTION public.%I(operation jsonb) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='''' AS $body$ BEGIN
    PERFORM 1 FROM coll_pat.projects WHERE id=(operation->>''project_id'')::uuid FOR UPDATE;
    PERFORM coll_pat.require_operator((operation->>''project_id'')::uuid);
    IF (%L=''coll_pat_apply'' AND operation->>''kind'' IN (''catalog'',''catalog_chunk'',''catalog_delete''))
     OR (%L=''coll_pat_delete_permanent'' AND operation->''payload''->>''kind''<>''inspection'')
     OR (%L=''coll_pat_patch_object'' AND EXISTS(SELECT 1 FROM jsonb_object_keys(operation->''payload''->''changes'') k WHERE k<>''under_asphalt'')) THEN
      PERFORM coll_pat.require_member((operation->>''project_id'')::uuid,true);
    END IF;
    RETURN coll_pat.%I(operation); END $body$',item,item,item,item,item||'_v021');
 END LOOP;
 IF to_regprocedure('coll_pat.coll_pat_photo_uploaded_v021(uuid,uuid,uuid)') IS NULL THEN
  SELECT pg_get_functiondef('public.coll_pat_photo_uploaded(uuid,uuid,uuid)'::regprocedure) INTO definition;
  EXECUTE replace(definition,'public.coll_pat_photo_uploaded(', 'coll_pat.coll_pat_photo_uploaded_v021(');
 END IF;
 IF to_regprocedure('coll_pat.coll_pat_storage_confirm_v021(uuid,uuid)') IS NULL THEN
  SELECT pg_get_functiondef('public.coll_pat_storage_confirm(uuid,uuid)'::regprocedure) INTO definition;
  EXECUTE replace(definition,'public.coll_pat_storage_confirm(', 'coll_pat.coll_pat_storage_confirm_v021(');
 END IF;
 IF to_regprocedure('coll_pat.coll_pat_reset_v021(uuid,bigint,text,uuid)') IS NULL THEN
  SELECT pg_get_functiondef('public.coll_pat_reset(uuid,bigint,text,uuid)'::regprocedure) INTO definition;
  EXECUTE replace(definition,'public.coll_pat_reset(', 'coll_pat.coll_pat_reset_v021(');
 END IF;
 IF to_regprocedure('coll_pat.coll_pat_backup_v021(uuid)') IS NULL THEN
  SELECT pg_get_functiondef('public.coll_pat_backup(uuid)'::regprocedure) INTO definition;
  EXECUTE replace(definition,'public.coll_pat_backup(', 'coll_pat.coll_pat_backup_v021(');
 END IF;
END $$;
CREATE OR REPLACE FUNCTION public.coll_pat_backup(p_project uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$ BEGIN
 PERFORM 1 FROM coll_pat.projects WHERE id=p_project FOR UPDATE;PERFORM coll_pat.require_member(p_project,true);
 RETURN coll_pat.coll_pat_backup_v021(p_project);END $$;
CREATE OR REPLACE FUNCTION public.coll_pat_reset(p_project uuid,p_generation bigint,p_confirmation text,p_backup_token uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$ BEGIN
 PERFORM 1 FROM coll_pat.projects WHERE id=p_project FOR UPDATE;PERFORM coll_pat.require_member(p_project,true);
 RETURN coll_pat.coll_pat_reset_v021(p_project,p_generation,p_confirmation,p_backup_token);END $$;
CREATE OR REPLACE FUNCTION public.coll_pat_photo_uploaded(p_project uuid,p_inspection uuid,p_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$ BEGIN
 PERFORM 1 FROM coll_pat.projects WHERE id=p_project FOR UPDATE;PERFORM coll_pat.require_operator(p_project);
 RETURN coll_pat.coll_pat_photo_uploaded_v021(p_project,p_inspection,p_id);END $$;
CREATE OR REPLACE FUNCTION public.coll_pat_storage_confirm(p_project uuid,p_photo uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$ BEGIN
 PERFORM 1 FROM coll_pat.projects WHERE id=p_project FOR UPDATE;PERFORM coll_pat.require_operator(p_project);
 RETURN coll_pat.coll_pat_storage_confirm_v021(p_project,p_photo);END $$;
CREATE OR REPLACE FUNCTION public.coll_pat_photo_access(object_name text,writing boolean DEFAULT false) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT EXISTS(SELECT 1 FROM coll_pat.inspection_photos p JOIN coll_pat.memberships m ON m.project_id=p.project_id AND m.user_id=auth.uid()
 JOIN coll_pat.inspections i ON i.project_id=p.project_id AND i.id=p.inspection_id
 WHERE p.storage_path=object_name AND p.archived_at IS NULL AND NOT i.cancelled
 AND (NOT writing OR (m.role IN ('admin','inspector') AND p.created_by=auth.uid() AND NOT p.uploaded)))
$$;
CREATE OR REPLACE FUNCTION public.coll_pat_photo_delete_access(object_name text) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT EXISTS(SELECT 1 FROM coll_pat.storage_deletions d JOIN coll_pat.memberships m ON m.project_id=d.project_id AND m.user_id=auth.uid()
 WHERE d.storage_path=object_name AND m.role IN ('admin','inspector') AND (d.requested_by=auth.uid() OR m.role='admin'))
$$;

CREATE TABLE IF NOT EXISTS coll_pat.access_requests(
 project_id uuid NOT NULL REFERENCES coll_pat.projects(id),user_id uuid NOT NULL REFERENCES auth.users(id),
 requested_at timestamptz NOT NULL DEFAULT now(),disabled_at timestamptz,PRIMARY KEY(project_id,user_id)
);
CREATE TABLE IF NOT EXISTS coll_pat.role_audit(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),project_id uuid NOT NULL REFERENCES coll_pat.projects(id),
 actor uuid NOT NULL,target uuid NOT NULL,previous_role text,new_role text,changed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
ALTER TABLE coll_pat.access_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE coll_pat.role_audit ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON coll_pat.access_requests,coll_pat.role_audit FROM PUBLIC,anon,authenticated;
INSERT INTO coll_pat.access_requests(project_id,user_id) SELECT project_id,user_id FROM coll_pat.memberships ON CONFLICT DO NOTHING;
CREATE OR REPLACE FUNCTION coll_pat.track_membership() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$ BEGIN
 INSERT INTO coll_pat.access_requests(project_id,user_id) VALUES(NEW.project_id,NEW.user_id) ON CONFLICT DO NOTHING;RETURN NEW;END $$;
DROP TRIGGER IF EXISTS coll_pat_membership_registry ON coll_pat.memberships;
CREATE TRIGGER coll_pat_membership_registry AFTER INSERT ON coll_pat.memberships FOR EACH ROW EXECUTE FUNCTION coll_pat.track_membership();

-- Only explicit COLL-PAT registrations are tracked; unrelated auth.users never become visible.
CREATE OR REPLACE FUNCTION coll_pat.track_registration() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE metadata jsonb:=to_jsonb(NEW)->'raw_user_meta_data';project uuid;BEGIN
 IF metadata->>'application'='COLL-PAT' AND metadata->>'coll_pat_project' ~ '^[0-9a-fA-F-]{36}$' THEN
  BEGIN project:=(metadata->>'coll_pat_project')::uuid;EXCEPTION WHEN invalid_text_representation THEN RETURN NEW;END;
  IF EXISTS(SELECT 1 FROM coll_pat.projects WHERE id=project) THEN INSERT INTO coll_pat.access_requests(project_id,user_id) VALUES(project,NEW.id) ON CONFLICT DO NOTHING;END IF;
 END IF;RETURN NEW;END $$;
DROP TRIGGER IF EXISTS coll_pat_registration ON auth.users;
CREATE TRIGGER coll_pat_registration AFTER INSERT OR UPDATE ON auth.users FOR EACH ROW EXECUTE FUNCTION coll_pat.track_registration();
INSERT INTO coll_pat.access_requests(project_id,user_id)
 SELECT p.id,u.id FROM auth.users u JOIN coll_pat.projects p ON p.id::text=(to_jsonb(u)->'raw_user_meta_data'->>'coll_pat_project')
 WHERE to_jsonb(u)->'raw_user_meta_data'->>'application'='COLL-PAT' ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION public.coll_pat_request_access(p_project uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$ BEGIN
 IF auth.uid() IS NULL THEN RAISE SQLSTATE 'PT401' USING MESSAGE='Autenticazione richiesta';END IF;
 IF NOT EXISTS(SELECT 1 FROM coll_pat.projects WHERE id=p_project) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='Progetto non disponibile';END IF;
 INSERT INTO coll_pat.access_requests(project_id,user_id) VALUES(p_project,auth.uid()) ON CONFLICT DO NOTHING;
 RETURN jsonb_build_object('requested',true);END $$;

CREATE OR REPLACE FUNCTION public.coll_pat_users(p_project uuid,p_query text DEFAULT '',p_after uuid DEFAULT NULL) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=coll_pat.require_member(p_project,true);rows jsonb;BEGIN
 SELECT coalesce(jsonb_agg(q.data ORDER BY q.id),'[]') INTO rows FROM (
  SELECT u.id,jsonb_build_object('id',u.id,'name',coalesce(to_jsonb(u)->'raw_user_meta_data'->>'full_name',to_jsonb(u)->'raw_user_meta_data'->>'name',''),
   'email',coalesce(to_jsonb(u)->>'email',''),'role',m.role,'state',CASE WHEN m.role IS NOT NULL THEN 'ENABLED' WHEN r.disabled_at IS NOT NULL THEN 'DISABLED' ELSE 'PENDING' END) data
  FROM auth.users u JOIN coll_pat.access_requests r ON r.user_id=u.id AND r.project_id=p_project
  LEFT JOIN coll_pat.memberships m ON m.project_id=p_project AND m.user_id=u.id
  WHERE (p_after IS NULL OR u.id>p_after) AND (coalesce(to_jsonb(u)->>'email','')||' '||coalesce(to_jsonb(u)->'raw_user_meta_data'->>'full_name','')||' '||coalesce(to_jsonb(u)->'raw_user_meta_data'->>'name','')) ILIKE '%'||left(p_query,200)||'%'
  ORDER BY u.id LIMIT 50) q;
 RETURN jsonb_build_object('items',rows,'next',CASE WHEN jsonb_array_length(rows)=50 THEN rows->49->>'id' ELSE NULL END,
 'scope','Membri e richieste esplicite COLL-PAT; registrazioni senza appartenenza verificabile escluse');END $$;

CREATE OR REPLACE FUNCTION public.coll_pat_set_role(p_project uuid,p_user uuid,p_role text,p_expected text,p_enabled boolean DEFAULT true) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid;previous text;next_role text;BEGIN
 PERFORM 1 FROM coll_pat.projects WHERE id=p_project FOR UPDATE;
 actor:=coll_pat.require_member(p_project,true);
 IF p_role IS NULL OR p_role NOT IN ('viewer','inspector','admin') OR p_enabled IS NULL THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Livello non valido';END IF;
 IF NOT EXISTS(SELECT 1 FROM coll_pat.access_requests WHERE project_id=p_project AND user_id=p_user) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='Utente estraneo al progetto';END IF;
 SELECT role INTO previous FROM coll_pat.memberships WHERE project_id=p_project AND user_id=p_user;
 IF previous IS DISTINCT FROM p_expected THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Livello cambiato: aggiornare elenco';END IF;
 next_role:=CASE WHEN p_enabled THEN p_role ELSE NULL END;
 IF p_user=actor AND next_role='admin' AND previous IS DISTINCT FROM 'admin' THEN RAISE SQLSTATE 'PT403' USING MESSAGE='Auto-promozione non consentita';END IF;
 IF previous='admin' AND next_role IS DISTINCT FROM 'admin' AND (SELECT count(*) FROM coll_pat.memberships WHERE project_id=p_project AND role='admin')<=1 THEN
  RAISE SQLSTATE 'PT409' USING MESSAGE='Ultimo amministratore: abilitare prima un altro amministratore';END IF;
 IF previous IS DISTINCT FROM next_role THEN
  IF next_role IS NULL THEN DELETE FROM coll_pat.memberships WHERE project_id=p_project AND user_id=p_user;
  ELSE INSERT INTO coll_pat.memberships(project_id,user_id,role) VALUES(p_project,p_user,next_role) ON CONFLICT(project_id,user_id) DO UPDATE SET role=excluded.role;END IF;
  UPDATE coll_pat.access_requests SET disabled_at=CASE WHEN next_role IS NULL THEN now() ELSE NULL END WHERE project_id=p_project AND user_id=p_user;
  INSERT INTO coll_pat.role_audit(project_id,actor,target,previous_role,new_role) VALUES(p_project,actor,p_user,previous,next_role);
 END IF;
 RETURN jsonb_build_object('id',p_user,'role',next_role,'state',CASE WHEN next_role IS NULL THEN 'DISABLED' ELSE 'ENABLED' END);END $$;

-- Keyset export cannot silently mix pages from different history revisions.
ALTER TABLE coll_pat.projects ADD COLUMN IF NOT EXISTS history_revision bigint NOT NULL DEFAULT 0;
CREATE OR REPLACE FUNCTION coll_pat.bump_history_revision() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$ BEGIN
 UPDATE coll_pat.projects SET history_revision=history_revision+1 WHERE id=coalesce(NEW.project_id,OLD.project_id);RETURN NULL;END $$;
DROP TRIGGER IF EXISTS coll_pat_history_revision ON coll_pat.inspections;
CREATE TRIGGER coll_pat_history_revision AFTER INSERT OR UPDATE OR DELETE ON coll_pat.inspections FOR EACH ROW EXECUTE FUNCTION coll_pat.bump_history_revision();
DROP TRIGGER IF EXISTS coll_pat_correction_revision ON coll_pat.cancellations;
CREATE TRIGGER coll_pat_correction_revision AFTER INSERT OR UPDATE OR DELETE ON coll_pat.cancellations FOR EACH ROW EXECUTE FUNCTION coll_pat.bump_history_revision();
CREATE OR REPLACE FUNCTION public.coll_pat_export_history(p_project uuid,p_after uuid DEFAULT NULL,p_snapshot text DEFAULT NULL,p_from timestamptz DEFAULT NULL,p_until timestamptz DEFAULT NULL) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=coll_pat.require_member(p_project);revision text;rows jsonb;BEGIN
 SELECT history_revision::text INTO revision FROM coll_pat.projects WHERE id=p_project FOR SHARE;
 IF p_snapshot IS NOT NULL AND p_snapshot<>revision THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Storico cambiato durante export: riprovare';END IF;
 IF p_from IS NOT NULL AND p_until IS NOT NULL AND p_from>=p_until THEN RAISE SQLSTATE 'PT400' USING MESSAGE='Periodo non valido';END IF;
 SELECT coalesce(jsonb_agg(coll_pat.inspection_json(q) ORDER BY q.id),'[]') INTO rows FROM (
  SELECT i.* FROM coll_pat.inspections i WHERE project_id=p_project AND status IN ('COMPLETO','IMPEDITO') AND NOT cancelled
   AND (p_after IS NULL OR id>p_after) AND (p_from IS NULL OR executed_at>=p_from) AND (p_until IS NULL OR executed_at<p_until)
  ORDER BY id LIMIT 200) q;
 RETURN jsonb_build_object('items',rows,'snapshot',revision,'next',CASE WHEN jsonb_array_length(rows)=200 THEN rows->199->>'id' ELSE NULL END);END $$;

REVOKE ALL ON ALL FUNCTIONS IN SCHEMA coll_pat FROM PUBLIC,anon,authenticated;
DO $$ DECLARE f record;BEGIN FOR f IN SELECT p.oid::regprocedure signature FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
 WHERE n.nspname='public' AND p.proname IN ('coll_pat_backup','coll_pat_reset','coll_pat_apply','coll_pat_save_inspection','coll_pat_patch_object','coll_pat_delete_permanent','coll_pat_photo_uploaded','coll_pat_storage_confirm','coll_pat_photo_access','coll_pat_photo_delete_access','coll_pat_request_access','coll_pat_users','coll_pat_set_role','coll_pat_export_history') LOOP
 EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC,anon',f.signature);EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO authenticated',f.signature);
END LOOP;END $$;
NOTIFY pgrst,'reload schema';
COMMIT;
