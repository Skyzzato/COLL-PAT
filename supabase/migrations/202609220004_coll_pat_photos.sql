-- Supabase Storage: private bucket, stable object names, insert once, no client overwrite/delete.
BEGIN;
CREATE OR REPLACE FUNCTION public.coll_pat_photo_access(object_name text,writing boolean DEFAULT false) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT EXISTS(SELECT 1 FROM coll_pat.inspection_photos p JOIN coll_pat.memberships m ON m.project_id=p.project_id AND m.user_id=auth.uid()
 JOIN coll_pat.inspections i ON i.project_id=p.project_id AND i.id=p.inspection_id
 WHERE p.storage_path=object_name AND p.archived_at IS NULL AND NOT i.cancelled AND (NOT writing OR (p.created_by=auth.uid() AND NOT p.uploaded)))
$$;
REVOKE ALL ON FUNCTION public.coll_pat_photo_access(text,boolean) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.coll_pat_photo_access(text,boolean) TO authenticated;
-- Plain PostgreSQL test environments may omit Storage. Operational Supabase always has these tables.
DO $$ BEGIN
 IF to_regclass('storage.buckets') IS NOT NULL AND to_regclass('storage.objects') IS NOT NULL THEN
   INSERT INTO storage.buckets(id,name,public,file_size_limit,allowed_mime_types) VALUES('coll-pat-photos','coll-pat-photos',false,6291456,ARRAY['image/jpeg']) ON CONFLICT(id) DO UPDATE SET public=false,file_size_limit=6291456,allowed_mime_types=ARRAY['image/jpeg'];
   DROP POLICY IF EXISTS coll_pat_photos_read ON storage.objects;
   DROP POLICY IF EXISTS coll_pat_photos_insert ON storage.objects;
   CREATE POLICY coll_pat_photos_read ON storage.objects FOR SELECT TO authenticated USING(bucket_id='coll-pat-photos' AND public.coll_pat_photo_access(name,false));
   CREATE POLICY coll_pat_photos_insert ON storage.objects FOR INSERT TO authenticated WITH CHECK(bucket_id='coll-pat-photos' AND public.coll_pat_photo_access(name,true));
 END IF;
END $$;
CREATE OR REPLACE FUNCTION public.coll_pat_photo_uploaded(p_project uuid,p_inspection uuid,p_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE u uuid:=coll_pat.require_member(p_project);p coll_pat.inspection_photos%ROWTYPE;exists_object boolean;
BEGIN
 SELECT * INTO p FROM coll_pat.inspection_photos WHERE project_id=p_project AND inspection_id=p_inspection AND id=p_id AND archived_at IS NULL;
 IF NOT FOUND OR p.created_by<>u THEN RAISE SQLSTATE 'PT403' USING MESSAGE='Photo permission denied'; END IF;
 EXECUTE 'SELECT EXISTS(SELECT 1 FROM storage.objects WHERE bucket_id=$1 AND name=$2)' INTO exists_object USING 'coll-pat-photos',p.storage_path;
 IF NOT exists_object THEN RAISE SQLSTATE 'PT409' USING MESSAGE='Foto non ancora caricata'; END IF;
 UPDATE coll_pat.inspection_photos SET uploaded=true WHERE project_id=p_project AND id=p_id;
 RETURN jsonb_build_object('id',p_id,'uploaded',true);
END $$;
REVOKE ALL ON FUNCTION public.coll_pat_photo_uploaded(uuid,uuid,uuid) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.coll_pat_photo_uploaded(uuid,uuid,uuid) TO authenticated;
NOTIFY pgrst,'reload schema';
COMMIT;
