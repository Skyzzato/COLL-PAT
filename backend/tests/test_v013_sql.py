"""Real PostgreSQL/PostGIS transactions and restricted authenticated role.

COLL_PAT_SQL_TEST_URL must reference an isolated local/CI postgres owner database.
The fixture creates a fresh database; never consumes .env or a Supabase project.
"""
import copy
import json
import os
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import pytest
import psycopg
from psycopg import sql
from psycopg.types.json import Jsonb

ROOT=Path(__file__).resolve().parents[2]
def uid():return str(uuid.uuid4())

@pytest.fixture(scope='module')
def database():
    url=os.environ.get('COLL_PAT_SQL_TEST_URL')
    if not url:pytest.skip('Dedicated COLL_PAT_SQL_TEST_URL not configured')
    from psycopg.conninfo import conninfo_to_dict,make_conninfo
    cfg=conninfo_to_dict(url)
    assert cfg.get('host') in ('127.0.0.1','localhost'), 'Only isolated local/CI test DB allowed'
    name='collpat_test_'+uuid.uuid4().hex[:12]
    with psycopg.connect(url,autocommit=True) as root:
        for role in ['anon','authenticated']:
            if not root.execute('select 1 from pg_roles where rolname=%s',(role,)).fetchone():root.execute(sql.SQL('CREATE ROLE {} NOLOGIN').format(sql.Identifier(role)))
        root.execute(sql.SQL('CREATE DATABASE {}').format(sql.Identifier(name)))
    cfg['dbname']=name;dsn=make_conninfo(**cfg)
    try:
        with psycopg.connect(dsn,autocommit=True) as c:
            c.execute("CREATE SCHEMA auth; CREATE TABLE auth.users(id uuid PRIMARY KEY); CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$ SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$; GRANT USAGE ON SCHEMA public,auth TO authenticated; GRANT EXECUTE ON FUNCTION auth.uid() TO authenticated;")
            for path in sorted((ROOT/'supabase/migrations').glob('20260922*.sql')):c.execute(path.read_text(encoding='utf-8'))
        yield dsn
    finally:
        with psycopg.connect(url,autocommit=True) as root:root.execute(sql.SQL('DROP DATABASE {} WITH (FORCE)').format(sql.Identifier(name)))

def rpc(dsn,user,function,**params):
    with psycopg.connect(dsn) as c:
        c.execute('SET LOCAL ROLE authenticated')
        c.execute("SELECT set_config('request.jwt.claim.sub',%s,true)",(user,))
        names=list(params)
        q=sql.SQL('SELECT public.{}({})').format(sql.Identifier(function),sql.SQL(',').join(sql.SQL('{} => %s').format(sql.Identifier(k)) for k in names))
        return c.execute(q,tuple(Jsonb(params[k]) if isinstance(params[k],(dict,list)) else params[k] for k in names)).fetchone()[0]

@pytest.fixture
def env(database):
    project,admin,operator,other=uid(),uid(),uid(),uid()
    with psycopg.connect(database) as c:
        for user in [admin,operator,other]:c.execute('INSERT INTO auth.users VALUES(%s)',(user,))
        c.execute('INSERT INTO coll_pat.projects(id,name,development) VALUES(%s,%s,true)',(project,'synthetic-v013-test'))
        for user,role in [(admin,'admin'),(operator,'inspector'),(other,'inspector')]:c.execute('INSERT INTO coll_pat.memberships VALUES(%s,%s,%s)',(project,user,role))
    seed=json.loads((ROOT/'demo/trento-lavis-v0.13.json').read_text(encoding='utf-8'))
    items=[dict(kind=kind,data=data) for array,kind in [('collectors','collector'),('points','point'),('segments','segment')] for data in seed[array]]
    operation=dict(operation_id=uid(),project_id=project,generation=0,payload_version=2,kind='catalog',payload=dict(items=items,provenance=None))
    rpc(database,admin,'coll_pat_apply',operation=operation)
    return dict(dsn=database,project=project,admin=admin,user=operator,other=other,point=seed['points'][0],seed=seed)

def inspection(e,model='ORDINARY',status='COMPLETO'):
    ident=uid();event=dict(id=uid(),inspection_id=ident,manhole_id=e['point']['id'],user_id=e['user'],device_id=uid(),dataset_id=uid(),rule_version='gps-1',app_version='0.13',requested_at='2026-01-01T09:00:00Z',acquired_at='2026-01-01T09:00:00Z',latitude=e['point']['latitude'],longitude=e['point']['longitude'],accuracy_m=2,age_s=0,permission='PRECISE',mock=False,error=None)
    sheet=dict(accessible=True,unsafe=False,opened=True,cleaning=False,no_open_reason='',anomaly_note='',exception_reason='Synthetic test',notes='Synthetic SQL test',impediment_reason='Road closed' if status=='IMPEDITO' else '',raise_needed=False,road_repair_needed=False)
    for k in ['cover','deposits','flow','walls','damage','closure','restored','surface','subsidence']:sheet[k]='REGOLARE'
    if model!='ORDINARY' or status=='IMPEDITO':
        sheet.update(opened=False,no_open_reason='Pozzetto sotto asfalto',cleaning=None)
        for k in ['deposits','flow','walls','damage']:sheet[k]='NON_OSSERVABILE'
    p=dict(id=ident,user_id=e['user'],project_id=e['project'],generation=0,payload_version=2,manhole_id=e['point']['id'],dataset_id=uid(),device_id=uid(),app_version='0.13',started_at='2026-01-01T09:00:00Z',completed_at='2026-01-01T09:01:00Z',model=model,status=status,periodic_control=status=='COMPLETO',sheet=sheet,events=[event],photos=[])
    return dict(operation_id=uid(),project_id=e['project'],generation=0,payload_version=2,kind='inspection',payload=p)

def apply(e,op,user=None):return rpc(e['dsn'],user or e['user'],'coll_pat_apply',operation=op)

def test_actual_roles_and_rls(env):
    e=env
    with psycopg.connect(e['dsn']) as c:
        assert c.execute("select bool_and(relrowsecurity) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='coll_pat' and c.relkind='r'").fetchone()[0]
        assert c.execute("select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where (n.nspname='coll_pat' or p.proname like 'coll_pat_%') and p.prosecdef and not ('search_path=\"\"'=any(p.proconfig))").fetchone()[0]==0
    for query in ["SELECT * FROM coll_pat.memberships","INSERT INTO coll_pat.memberships VALUES(gen_random_uuid(),gen_random_uuid(),'admin')","SELECT * FROM coll_pat.legacy_identity_links"]:
        with psycopg.connect(e['dsn']) as c:
            c.execute('SET LOCAL ROLE authenticated')
            with pytest.raises(psycopg.errors.InsufficientPrivilege):c.execute(query)
    with pytest.raises(psycopg.Error,match='Auth required'):rpc(e['dsn'],'','coll_pat_status',p_project=e['project'])
    with pytest.raises(psycopg.Error,match='permission denied'):rpc(e['dsn'],uid(),'coll_pat_status',p_project=e['project'])

def test_distinct_visits_lost_receipt_and_content_conflict(env):
    e=env;op=inspection(e);receipt=apply(e,op)
    assert apply(e,op)==receipt  # lost response after committed transaction
    apply(e,inspection(e))
    rows=rpc(e['dsn'],e['user'],'coll_pat_history',p_project=e['project'])['items'];assert len(rows)==2
    changed=copy.deepcopy(op);changed['payload']['sheet']['notes']='Different'
    with pytest.raises(psycopg.Error,match='different identity/content'):apply(e,changed)
    with pytest.raises(psycopg.Error,match='different identity/content'):apply(e,op,e['other'])

def test_two_workers_receive_same_receipt(env):
    e=env;op=inspection(e)
    with ThreadPoolExecutor(2) as pool:results=list(pool.map(lambda _:apply(e,op),range(2)))
    assert results[0]==results[1]
    assert len(rpc(e['dsn'],e['user'],'coll_pat_history',p_project=e['project'])['items'])==1

@pytest.mark.parametrize('model,status,valid',[('ORDINARY','COMPLETO',True),('ASPHALT_EXTERNAL','COMPLETO',True),('ORDINARY','IMPEDITO',False)])
def test_models_and_failed_gps_attempt(env,model,status,valid):
    e=env;op=inspection(e,model,status);ev=op['payload']['events'][0];ev.update(latitude=None,longitude=None,accuracy_m=None,age_s=None,permission='DENIED',error='permesso negato')
    if model=='ASPHALT_EXTERNAL':op['payload']['sheet'].update(surface='ANOMALO',anomaly_note='Avvallamento sintetico')
    r=apply(e,op);assert r['server_gps']['state']=='NON_DISPONIBILE'
    rows=rpc(e['dsn'],e['user'],'coll_pat_history',p_project=e['project'])['items'];assert rows[0]['periodic_control']==valid
    assert rows[0]['original']['events'][0]['error']=='permesso negato'

def test_invalid_models_authorship_and_photo_paths(env):
    e=env
    mutations=[lambda p:p.update(status='PARZIALE'),lambda p:p.update(user_id=e['other']),lambda p:p['sheet'].update(opened=False),lambda p:p.update(photos=[dict(upload='SIMULATED_LOCAL_ONLY',localUri='content://private')]),lambda p:p['events'][0].update(user_id=e['other'])]
    for mutate in mutations:
        op=inspection(e);mutate(op['payload'])
        with pytest.raises(psycopg.Error):apply(e,op)
    op=inspection(e,'ASPHALT_EXTERNAL');op['payload']['sheet']['walls']='REGOLARE'
    with pytest.raises(psycopg.Error,match='Unobserved internal'):apply(e,op)

def cancellation(e,op,event=None):
    return dict(operation_id=uid(),project_id=e['project'],generation=0,payload_version=2,kind='cancel',payload=dict(id=uid(),inspection_id=op['payload']['id'],event_id=event,author=e['user'],at='2026-01-01T10:00:00Z',reason='Synthetic correction'))

def test_offline_cancellation_order_and_preserved_original(env):
    e=env;op=inspection(e);cancel=cancellation(e,op)
    with pytest.raises(psycopg.Error,match='Creation must'):apply(e,cancel)
    apply(e,op)
    event=cancellation(e,op,op['payload']['events'][0]['id']);apply(e,event);apply(e,cancel)
    apply(e,op) # retry creation cannot resurrect
    row=rpc(e['dsn'],e['user'],'coll_pat_history',p_project=e['project'])['items'][0]
    assert row['cancelled'] and row['original']==op['payload'] and len(row['corrections'])==2
    unauthorized=cancellation(e,op);unauthorized['payload']['author']=e['other']
    with pytest.raises(psycopg.Error,match='permission denied'):apply(e,unauthorized,e['other'])

def test_reset_generation_backup_race_and_scope(env):
    e=env;first=inspection(e);apply(e,first)
    backup=rpc(e['dsn'],e['admin'],'coll_pat_backup',p_project=e['project']);second=inspection(e);apply(e,second)
    with pytest.raises(psycopg.Error,match='Fresh backup'):rpc(e['dsn'],e['admin'],'coll_pat_reset',p_project=e['project'],p_generation=0,p_confirmation='AZZERA',p_backup_token=backup['backup_token'])
    backup=rpc(e['dsn'],e['admin'],'coll_pat_backup',p_project=e['project'])
    with pytest.raises(psycopg.Error,match='permission denied'):rpc(e['dsn'],e['user'],'coll_pat_reset',p_project=e['project'],p_generation=0,p_confirmation='AZZERA',p_backup_token=backup['backup_token'])
    result=rpc(e['dsn'],e['admin'],'coll_pat_reset',p_project=e['project'],p_generation=0,p_confirmation='AZZERA',p_backup_token=backup['backup_token'])
    assert result['count']==2 and result['generation']==1
    for stale in [first,second,inspection(e)]:
        with pytest.raises(psycopg.Error,match='RESET_OBSOLETE'):apply(e,stale)
    assert len(rpc(e['dsn'],e['admin'],'coll_pat_catalog',p_project=e['project'])['items'])==32
    with psycopg.connect(e['dsn']) as c:
        assert c.execute('SELECT count(*) FROM coll_pat.reset_log WHERE project_id=%s',(e['project'],)).fetchone()[0]==1
        assert c.execute('SELECT count(*) FROM coll_pat.memberships WHERE project_id=%s',(e['project'],)).fetchone()[0]==3

def test_catalogue_defaults_rename_declarations_and_no_privilege_escalation(env):
    e=env;c=copy.deepcopy(e['seed']['collectors'][0]);c.update(code="BOE' 001",description='Renamed',type="BOE'",length_source='DECLARED',length_m=123.5)
    op=dict(operation_id=uid(),project_id=e['project'],generation=0,payload_version=2,kind='catalog',payload=dict(items=[dict(kind='collector',data=c)]))
    with pytest.raises(psycopg.Error,match='permission denied'):apply(e,op)
    apply(e,op,e['admin']);page=rpc(e['dsn'],e['user'],'coll_pat_catalog',p_project=e['project']);saved=next(i['data'] for i in page['items'] if i['data']['id']==c['id']);assert saved['code']=="BOE' 001" and saved['length_m']==123.5
    c['length_m']=12;c['length_source']='MEASURED';op['operation_id']=uid();op['payload']['provenance']=dict(id=uid(),source='synthetic-test',hash='test',mapping={},report={})
    apply(e,op,e['admin']);page2=rpc(e['dsn'],e['user'],'coll_pat_catalog',p_project=e['project']);saved=next(i['data'] for i in page2['items'] if i['data']['id']==c['id']);assert saved['length_m']==123.5
    with pytest.raises(psycopg.Error,match='Catalog changed'):rpc(e['dsn'],e['user'],'coll_pat_catalog',p_project=e['project'],p_revision=page['revision'],p_offset=0)

def test_staged_import_is_private_atomic_and_retryable(env):
    e=env;batch=uid();c=copy.deepcopy(e['seed']['collectors'][0]);c.update(id=uid(),code='STAGED-001',description='Staged synthetic')
    stage=dict(operation_id=uid(),project_id=e['project'],generation=0,payload_version=2,kind='catalog_chunk',payload=dict(batch_id=batch,index=0,items=[dict(kind='collector',data=c)]))
    with pytest.raises(psycopg.Error,match='permission denied'):apply(e,stage)
    receipt=apply(e,stage,e['admin']);assert apply(e,stage,e['admin'])==receipt
    assert all(i['data']['id']!=c['id'] for i in rpc(e['dsn'],e['user'],'coll_pat_catalog',p_project=e['project'])['items'])
    final=dict(operation_id=uid(),project_id=e['project'],generation=0,payload_version=2,kind='catalog',payload=dict(batch_id=batch,chunks=[stage['operation_id'],uid()],provenance=None))
    with pytest.raises(psycopg.Error,match='Staging incomplete'):apply(e,final,e['admin'])
    final['payload']['chunks'].pop();r=apply(e,final,e['admin']);assert apply(e,final,e['admin'])==r
    assert any(i['data']['id']==c['id'] for i in rpc(e['dsn'],e['user'],'coll_pat_catalog',p_project=e['project'])['items'])

def test_server_recomputes_latest_gps_and_mock_approximate(env):
    e=env
    for permission,mock in [('APPROXIMATE',False),('PRECISE',True)]:
        op=inspection(e);op['payload']['events'][0].update(permission=permission,mock=mock,local_evaluation={'state':'COMPATIBILE'})
        assert apply(e,op)['server_gps']['state']=='INCERTA'
    op=inspection(e);last=copy.deepcopy(op['payload']['events'][0]);last.update(id=uid(),acquired_at='2026-01-01T09:00:01Z',latitude=None,error='GPS spento');op['payload']['events'].append(last)
    assert apply(e,op)['server_gps']['state']=='NON_DISPONIBILE'

def test_server_rejects_null_fields_and_shared_segment_length_counted_once(env):
    e=env;c=copy.deepcopy(e['seed']['collectors'][0]);c['code']=None
    op=dict(operation_id=uid(),project_id=e['project'],generation=0,payload_version=2,kind='catalog',payload=dict(items=[dict(kind='collector',data=c)]))
    with pytest.raises(psycopg.Error,match='Invalid collector'):apply(e,op,e['admin'])
    before=rpc(e['dsn'],e['user'],'coll_pat_catalog',p_project=e['project'])
    cid=c['id'];length=next(i['data']['length_m'] for i in before['items'] if i['data']['id']==cid)
    duplicate=copy.deepcopy(e['seed']['segments'][0]);duplicate['id']=uid();op['payload']['items']=[dict(kind='segment',data=duplicate)]
    apply(e,op,e['admin']);after=rpc(e['dsn'],e['user'],'coll_pat_catalog',p_project=e['project'])
    assert next(i['data']['length_m'] for i in after['items'] if i['data']['id']==cid)==pytest.approx(length,abs=.01)

def test_reset_holds_lock_against_second_offline_phone(env):
    import time
    e=env;backup=rpc(e['dsn'],e['admin'],'coll_pat_backup',p_project=e['project']);op=inspection(e)
    with psycopg.connect(e['dsn']) as reset_connection:
        reset_connection.execute('SET LOCAL ROLE authenticated')
        reset_connection.execute("SELECT set_config('request.jwt.claim.sub',%s,true)",(e['admin'],))
        reset_connection.execute('SELECT public.coll_pat_reset(%s,0,%s,%s)',(e['project'],'AZZERA',backup['backup_token']))
        def old_phone():
            with psycopg.connect(e['dsn'],application_name='collpat-stale-phone') as c:
                c.execute('SET LOCAL ROLE authenticated');c.execute("SELECT set_config('request.jwt.claim.sub',%s,true)",(e['user'],))
                return c.execute('SELECT public.coll_pat_apply(%s)',(Jsonb(op),)).fetchone()[0]
        with ThreadPoolExecutor(1) as pool:
            future=pool.submit(old_phone)
            with psycopg.connect(e['dsn'],autocommit=True) as observer:
                for _ in range(100):
                    if observer.execute("SELECT 1 FROM pg_stat_activity WHERE application_name='collpat-stale-phone' AND wait_event_type='Lock'").fetchone():break
                    time.sleep(.02)
                else:pytest.fail('Old phone did not block on the reset lock')
            reset_connection.commit()
            with pytest.raises(psycopg.Error,match='RESET_OBSOLETE'):future.result(timeout=10)

@pytest.mark.parametrize('timestamp,day,semester',[('2026-06-30T22:30:00Z','2026-07-01',2),('2026-03-29T01:30:00Z','2026-03-29',1),('2026-01-01T23:30:00Z','2026-01-02',1)])
def test_execution_rome_not_upload_time(env,timestamp,day,semester):
    e=env;op=inspection(e);op['payload'].update(started_at=timestamp,completed_at=timestamp);apply(e,op)
    row=rpc(e['dsn'],e['user'],'coll_pat_history',p_project=e['project'])['items'][0]
    assert row['execution_day']==day and row['semester']==semester
