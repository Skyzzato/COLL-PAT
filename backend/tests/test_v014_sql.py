"""v0.14 real SQL/RLS/CAS tests on a disposable local PostgreSQL database.
Storage tables here are a policy harness, not a running Supabase Storage service.
"""
import copy
import json
from concurrent.futures import ThreadPoolExecutor
import pytest
import psycopg
from psycopg.types.json import Jsonb
from test_v013_sql import database, env as old_env, rpc, inspection, uid, ROOT

@pytest.fixture(scope='module')
def db14(database):
    with psycopg.connect(database,autocommit=True) as c:
        c.execute("""CREATE SCHEMA storage;
        CREATE TABLE storage.buckets(id text PRIMARY KEY,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]);
        CREATE TABLE storage.objects(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),bucket_id text,name text,UNIQUE(bucket_id,name));
        ALTER TABLE storage.objects ENABLE ROW LEVEL SECURITY;
        GRANT USAGE ON SCHEMA storage TO authenticated;
        GRANT SELECT,INSERT,UPDATE,DELETE ON storage.objects TO authenticated;""")
        # Applying twice proves the new migrations preserve existing schema/configuration.
        for _ in range(2):
            for path in sorted((ROOT/'supabase/migrations').glob('20260922000[34]*.sql')):c.execute(path.read_text(encoding='utf-8'))
    return database

@pytest.fixture
def e14(db14):return old_env.__wrapped__(db14)

def operation(e,revision=0,body=None):
    op=inspection(e) if body is None else dict(operation_id=uid(),project_id=e['project'],generation=0,payload_version=2,payload=copy.deepcopy(body))
    op.update(kind='shared_inspection',app_version='0.14')
    p=op['payload'];p.update(status='BOZZA',expected_revision=revision,app_version='0.14',periodic_control=False)
    p.pop('completed_at',None)
    p['events'][0]['applied_limits']=dict(max_accuracy_m=10,radius_m=15)
    return op

def save(e,op,user=None):return rpc(e['dsn'],user or e['user'],'coll_pat_save_inspection',operation=op)
def load(e,id,user=None):return rpc(e['dsn'],user or e['user'],'coll_pat_inspection',p_project=e['project'],p_id=id)

def test_shared_draft_second_user_photo_and_submission(e14):
    e=e14;op=operation(e);r=save(e,op);assert r['revision']==1
    assert save(e,op)==r # lost response retry is idempotent
    row=load(e,op['payload']['id'],e['other']);assert row['status']=='BOZZA' and row['created_by']==e['user']
    updated=operation(e,1,row['original']);updated['payload']['sheet']['notes']='Second operator'
    photo=uid();updated['payload']['photos']=[dict(id=photo,created_at='2026-09-22T12:00:00Z')]
    r2=save(e,updated,e['other']);assert r2['revision']==2 and r2['created_by']==e['user'] and r2['updated_by']==e['other']
    row=load(e,op['payload']['id']);assert row['photos'][0]['id']==photo
    path=row['photos'][0]['storage_path']
    with psycopg.connect(e['dsn']) as c:
        c.execute('SET LOCAL ROLE authenticated');c.execute("SELECT set_config('request.jwt.claim.sub',%s,true)",(e['other'],))
        c.execute('INSERT INTO storage.objects(bucket_id,name) VALUES(%s,%s)',('coll-pat-photos',path))
    rpc(e['dsn'],e['other'],'coll_pat_photo_uploaded',p_project=e['project'],p_inspection=row['id'],p_id=photo)
    final=operation(e,2,row['original']);final['payload'].update(status='COMPLETO',periodic_control=True,completed_at='2026-01-01T09:01:00Z')
    rf=save(e,final,e['other']);assert rf['submitted_by']==e['other'] and rf['created_by']==e['user']
    with pytest.raises(psycopg.Error,match='Bozza aggiornata'):save(e,operation(e,3,final['payload']))
    assert load(e,row['id'])['photos'][0]['uploaded'] is True

def test_concurrent_draft_edits_one_winner(e14):
    e=e14;op=operation(e);save(e,op);a=operation(e,1,op['payload']);b=operation(e,1,op['payload']);a['payload']['sheet']['notes']='A';b['payload']['sheet']['notes']='B'
    def edit(op):
        try:return save(e,op)
        except psycopg.Error as err:return err.sqlstate
    with ThreadPoolExecutor(2) as pool:results=list(pool.map(edit,[a,b]))
    assert sum(isinstance(r,dict) for r in results)==1 and 'PT409' in results
    assert load(e,op['payload']['id'])['revision']==2

def test_archive_retains_history_and_stale_upload_cannot_resurrect(e14):
    e=e14;op=operation(e);save(e,op);collector=copy.deepcopy(e['seed']['collectors'][0]);collector['archived']=True
    def catalog(c):return rpc(e['dsn'],e['admin'],'coll_pat_apply',operation=dict(operation_id=uid(),project_id=e['project'],generation=0,payload_version=2,kind='catalog',payload=dict(items=[dict(kind='collector',data=c)])))
    catalog(collector);active=rpc(e['dsn'],e['user'],'coll_pat_catalog',p_project=e['project'])
    assert collector['id'] not in [r['data']['id'] for r in active['items']]
    assert active['archived'][0]['data']['archived_at'] and active['archived'][0]['data']['archived_by']==e['admin']
    assert load(e,op['payload']['id'])['manhole_id']==e['point']['id']
    collector['archived']=False;catalog(collector)
    with psycopg.connect(e['dsn']) as c:assert c.execute("SELECT data->>'archived' FROM coll_pat.catalog WHERE project_id=%s AND id=%s",(e['project'],collector['id'])).fetchone()[0]=='true'

@pytest.mark.parametrize('accuracy,valid',[(5,True),(10,True),(10.1,False)])
def test_server_accuracy_boundary(e14,accuracy,valid):
    e=e14;op=operation(e);op['payload'].update(status='COMPLETO',periodic_control=True,completed_at='2026-01-01T09:01:00Z');op['payload']['events'][0]['accuracy_m']=accuracy
    if valid:assert save(e,op)['server_gps']['gps_reliability']=='RELIABLE'
    else:
        with pytest.raises(psycopg.Error,match='GPS non affidabile'):save(e,op)

@pytest.mark.parametrize('distance,valid',[(8,True),(15,True),(16,False)])
def test_server_distance_independent(e14,distance,valid):
    import math
    e=e14;op=operation(e);op['payload'].update(status='COMPLETO',periodic_control=True,completed_at='2026-01-01T09:01:00Z')
    op['payload']['events'][0]['latitude']+=math.degrees((distance-1e-8)/6371008.8)
    if valid:assert save(e,op)['server_gps']['gps_reliability']=='RELIABLE'
    else:
        with pytest.raises(psycopg.Error,match='GPS non affidabile'):save(e,op)

def test_version_gate_readonly_anon_config_and_rls(e14):
    e=e14
    with psycopg.connect(e['dsn']) as c:
        assert c.execute("SELECT bool_and(relrowsecurity) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='coll_pat' AND c.relkind='r'").fetchone()[0]
        c.execute('SET LOCAL ROLE anon');assert c.execute('SELECT public.coll_pat_version()').fetchone()[0]['minimum_supported_version']=='0.14'
        with pytest.raises(psycopg.errors.InsufficientPrivilege):c.execute('UPDATE coll_pat.app_config SET minimum_supported_version=\'0.1\'')
    for version in ['0.13','0.9','']:
        with psycopg.connect(e['dsn']) as c:
            c.execute('SET LOCAL ROLE authenticated');c.execute("SELECT set_config('request.jwt.claim.sub',%s,true)",(e['user'],));c.execute("SELECT set_config('request.headers',%s,true)",(json.dumps({'x-coll-pat-version':version}),))
            with pytest.raises(psycopg.Error,match='non supportata'):c.execute('SELECT public.coll_pat_status(%s)',(e['project'],))
    with pytest.raises(psycopg.Error,match='permission denied'):rpc(e['dsn'],uid(),'coll_pat_history',p_project=e['project'])

def test_photos_other_project_cannot_read_or_upload(e14):
    e=e14;op=operation(e);photo=uid();op['payload']['photos']=[dict(id=photo)];save(e,op);path=load(e,op['payload']['id'])['photos'][0]['storage_path']
    with psycopg.connect(e['dsn']) as c:
        c.execute('SET LOCAL ROLE authenticated');c.execute("SELECT set_config('request.jwt.claim.sub',%s,true)",(uid(),))
        assert not c.execute('SELECT public.coll_pat_photo_access(%s,false)',(path,)).fetchone()[0]
        with pytest.raises(psycopg.errors.InsufficientPrivilege):c.execute('INSERT INTO storage.objects(bucket_id,name) VALUES(%s,%s)',('coll-pat-photos',path))

def test_summary_returns_latest_regular_not_old_anomaly(e14):
    e=e14
    for date,anomaly in [('2026-01-01T09:01:00Z','Old anomaly'),('2026-02-01T09:01:00Z','')]:
        op=operation(e);op['payload'].update(status='COMPLETO',periodic_control=True,completed_at=date);op['payload']['sheet']['anomaly_note']=anomaly;save(e,op)
    rows=rpc(e['dsn'],e['user'],'coll_pat_inspection_summary',p_project=e['project'])['items']
    assert len(rows)==1 and rows[0]['original']['sheet']['anomaly_note']==''

def test_submitter_can_cancel_shared_inspection_without_losing_creator(e14):
    e=e14;draft=operation(e);save(e,draft)
    final=operation(e,1,draft['payload']);final['payload'].update(status='COMPLETO',periodic_control=True,completed_at='2026-01-01T09:01:00Z')
    save(e,final,e['other'])
    cancel=dict(operation_id=uid(),project_id=e['project'],generation=0,payload_version=2,kind='cancel',payload=dict(id=uid(),inspection_id=draft['payload']['id'],event_id=None,author=e['other'],at='2026-09-22T12:00:00Z',reason='Prova annullata'))
    receipt=rpc(e['dsn'],e['other'],'coll_pat_apply',operation=cancel)
    assert rpc(e['dsn'],e['other'],'coll_pat_apply',operation=cancel)==receipt
    row=load(e,draft['payload']['id']);assert row['cancelled'] and row['created_by']==e['user'] and row['submitted_by']==e['other']
    summary=rpc(e['dsn'],e['user'],'coll_pat_inspection_summary',p_project=e['project'])
    assert summary['items']==[] and draft['payload']['id'] in summary['cancelled_ids']
