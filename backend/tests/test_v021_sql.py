"""v0.21 uses disposable local DBs only; no remote credentials or operational fixtures."""
import copy
import pytest
import psycopg
from test_v013_sql import ROOT, database, env as old_env, rpc, uid
from test_v014_sql import db14, save, load
from test_v015_sql import db15, averaged
from test_v02_sql import db02, apply_catalog

@pytest.fixture(scope='module')
def db21(db02):
    with psycopg.connect(db02, autocommit=True) as c:
        for _ in range(2):
            for name in ['202609230008_coll_pat_v021_lifecycle.sql','202609230009_coll_pat_v021_gps.sql']:
                c.execute((ROOT/'supabase/migrations'/name).read_text(encoding='utf-8'))
    return db02

@pytest.fixture
def e21(db21):return old_env.__wrapped__(db21)

def deletion(e,kind='collector',target=None,user=None):
    target=target or e['point']['collectors'][0]
    scope=rpc(e['dsn'],user or e['admin'],'coll_pat_deletion_preview',p_project=e['project'],p_kind=kind,p_id=target)
    return dict(operation_id=uid(),project_id=e['project'],generation=0,payload_version=2,kind='permanent_delete',app_version='0.21',payload=dict(id=target,kind=kind,confirmed=True,scope=scope))

def execute(e,op,user=None):return rpc(e['dsn'],user or e['admin'],'coll_pat_delete_permanent',operation=op)

def modern(e,draft=False):
    op=averaged(e,draft=draft);op['app_version']='0.21';p=op['payload'];p.update(app_version='0.21',gps_contract=2,sheet_version='sheet-3',gps_state={'status':'RECORDED'})
    event=p['events'][0];event.update(method='SPHERICAL_MEAN_MEAN_ACCURACY_V2',stabilization_ms=3000,sampling_ms=5000,duration_ms=8000,sample_span_ms=4000,ended_elapsed_ns=9000000000,last_sample_elapsed_ns=8000000000,age_s=1,dispersion_m=0,acquisition_ended_at='2026-01-01T09:00:08Z')
    event['applied_limits'].update(max_accuracy_m=20,radius_m=20)
    event['samples']=[dict(elapsed_ns=t*1000000000,latitude=event['latitude'],longitude=event['longitude'],accuracy_m=event['accuracy_m'],mock=False) for t in range(4,9)]
    return op

def test_permanent_removes_all_payload_copies_and_replay(e21):
    e=e21;op=averaged(e,draft=True);pid=uid();op['payload']['photos']=[dict(id=pid,created_at='2026-09-23T10:00:00Z')];save(e,op)
    d=deletion(e);assert e['point']['id'] in d['payload']['scope']['points'];assert 'segments' in d['payload']['scope'];r=execute(e,d);assert r['database_deleted'] and r['storage_pending']==1
    assert execute(e,d)==r
    assert save(e,op)['content_deleted']
    with psycopg.connect(e['dsn']) as c:
        for table in ['inspections','inspection_photos','cancellations']:
            assert c.execute(f'SELECT count(*) FROM coll_pat.{table} WHERE project_id=%s',(e['project'],)).fetchone()[0]==0
        assert c.execute('SELECT count(*) FROM coll_pat.receipts WHERE operation_id=%s',(op['operation_id'],)).fetchone()[0]==0
        assert c.execute('SELECT count(*) FROM coll_pat.catalog WHERE project_id=%s AND id=%s',(e['project'],e['point']['id'])).fetchone()[0]==0
    resurrect=copy.deepcopy(op);resurrect['operation_id']=uid()
    with pytest.raises(psycopg.Error):save(e,resurrect)

def test_scope_change_does_not_delete_new_inspection(e21):
    e=e21;d=deletion(e);save(e,averaged(e,draft=True))
    with pytest.raises(psycopg.Error,match='Ambito'):execute(e,d)
    with psycopg.connect(e['dsn']) as c:assert c.execute('SELECT count(*) FROM coll_pat.inspections WHERE project_id=%s',(e['project'],)).fetchone()[0]==1

def test_permissions_and_inspection_only(e21):
    e=e21;op=averaged(e,draft=True);save(e,op)
    with pytest.raises(psycopg.Error,match='permission'):deletion(e,user=e['user'])
    d=deletion(e,'inspection',op['payload']['id'],e['user']);execute(e,d,e['user'])
    with psycopg.connect(e['dsn']) as c:assert c.execute('SELECT count(*) FROM coll_pat.catalog WHERE project_id=%s AND id=%s',(e['project'],e['point']['id'])).fetchone()[0]==1

def test_same_source_two_new_lifecycles_old_ids_never_return(e21):
    e=e21;original=copy.deepcopy(e['seed']['collectors'][0]);original.update(id=uid(),code='CYCLE',source_identity='fixture|collector|same')
    previous=[]
    for _ in range(3):
        c=copy.deepcopy(original);c['id']=uid();apply_catalog(e,[dict(kind='collector',data=c)])
        apply_catalog(e,[dict(kind='collector',data=c)])
        for old in previous:
            with pytest.raises(psycopg.Error):apply_catalog(e,[dict(kind='collector',data=old)])
        execute(e,deletion(e,target=c['id']));previous.append(c)

def test_v2_mean_is_checked_and_no_gps_is_distinct(e21):
    e=e21;op=modern(e);assert save(e,op)['server_gps']['gps_reliability']=='RELIABLE'
    op=modern(e);p=op['payload'];p['events']=[];p['gps_state']=dict(status='NOT_RECORDED_WITH_REASON',reason='Nessun fix utilizzabile',author=e['user'],attempt_id=uid(),attempted_at='2026-01-01T09:00:00Z',confirmed_at='2026-01-01T09:00:40Z')
    assert save(e,op)['server_gps']['gps_reliability']=='NOT_RECORDED_WITH_REASON'
    assert load(e,p['id'])['original']['events']==[]

@pytest.mark.parametrize('bad',['stabilization','duplicate','mean','method','radius','coexist'])
def test_v2_rejects_fabricated_aggregation(e21,bad):
    op=modern(e21);event=op['payload']['events'][0]
    if bad=='stabilization':event['samples'][0]['elapsed_ns']=2000000000
    if bad=='duplicate':event['samples'][0]=copy.deepcopy(event['samples'][1])
    if bad=='mean':event['accuracy_m']=1
    if bad=='method':event['method']='UNVERIFIED'
    if bad=='radius':event['applied_limits']['radius_m']=31
    if bad=='coexist':op['payload']['gps_state']=dict(status='NOT_RECORDED_WITH_REASON',reason='No GPS')
    with pytest.raises(psycopg.Error):save(e21,op)

def patch(e,target,changes,expected,user=None):
    op=dict(operation_id=uid(),project_id=e['project'],generation=0,payload_version=2,kind='object_patch',app_version='0.21',payload=dict(id=target,changes=changes,expected=expected))
    return rpc(e['dsn'],user or e['admin'],'coll_pat_patch_object',operation=op)


def test_narrow_patch_permissions_inheritance_and_conflict(e21):
    e=e21;point=e['point'];cid=point['collectors'][0]
    patch(e,point['id'],{'under_asphalt':True},{'under_asphalt':point.get('under_asphalt')},e['user'])
    with pytest.raises(psycopg.Error):patch(e,point['id'],{'latitude':0},{'latitude':point['latitude']},e['user'])
    with psycopg.connect(e['dsn']) as c:color=c.execute("SELECT data->'display_color' FROM coll_pat.catalog WHERE project_id=%s AND id=%s",(e['project'],cid)).fetchone()[0]
    patch(e,cid,{'display_color':None,'display_width':None,'symbol':None},{'display_color':color,'display_width':None,'symbol':None})
    with psycopg.connect(e['dsn']) as c:assert c.execute("SELECT data->>'display_color' FROM coll_pat.catalog WHERE project_id=%s AND id=%s",(e['project'],cid)).fetchone()[0] is None
    with pytest.raises(psycopg.Error,match='altro dispositivo'):patch(e,point['id'],{'under_asphalt':False},{'under_asphalt':False},e['user'])


def test_shared_point_survives_collector_and_point_purge_removes_incident_segments(e21):
    e=e21;c=copy.deepcopy(e['seed']['collectors'][0]);c.update(id=uid(),code='KEEP')
    p=copy.deepcopy(e['point']);p['collectors'].append(c['id'])
    apply_catalog(e,[dict(kind='collector',data=c),dict(kind='point',data=p)])
    execute(e,deletion(e,target=p['collectors'][0]))
    with psycopg.connect(e['dsn']) as db:
        row=db.execute('SELECT data FROM coll_pat.catalog WHERE project_id=%s AND id=%s',(e['project'],p['id'])).fetchone()[0]
        assert row['collectors']==[c['id']]
    execute(e,deletion(e,kind='point',target=p['id']))
    with psycopg.connect(e['dsn']) as db:assert db.execute('SELECT count(*) FROM coll_pat.catalog WHERE project_id=%s AND id=%s',(e['project'],c['id'])).fetchone()[0]==1


def test_missing_confirmation_arrays_and_draft_identity_are_rejected(e21):
    op=deletion(e21);op['payload']['scope']={}
    with pytest.raises(psycopg.Error):execute(e21,op)
    op=modern(e21,draft=True);op['payload']['events'][0]['inspection_id']=uid()
    with pytest.raises(psycopg.Error,match='identity mismatch'):save(e21,op)


def test_archival_maintenance_preview_repeat_and_other_project_protection(e21,monkeypatch):
    import importlib.util
    spec=importlib.util.spec_from_file_location('maintenance',ROOT/'scripts/purge_archived_v021.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
    e=e21;monkeypatch.setattr(m,'PROJECT',e['project']);other=old_env.__wrapped__(e['dsn'])
    with psycopg.connect(e['dsn']) as c:
        c.execute("UPDATE coll_pat.projects SET name='COLL-PAT' WHERE id=%s",(e['project'],))
        cid=e['point']['collectors'][0]
        c.execute("UPDATE coll_pat.catalog SET data=data||'{\"archived\":true}' WHERE project_id=%s AND id=%s",(e['project'],cid))
        before=c.execute('SELECT jsonb_agg(to_jsonb(x) ORDER BY id) FROM coll_pat.catalog x WHERE project_id=%s',(other['project'],)).fetchone()[0]
        plan,fp=m.preview(c,e['project']);assert cid in plan['removed'];assert plan['inspections']==[]
        with pytest.raises(ValueError):m.preview(c,other['project'])
        with pytest.raises(ValueError):m.execute(c,e['project'],e['admin'],'obsolete-fingerprint')
        result=m.execute(c,e['project'],e['admin'],fp);assert result['database_purge_executed']
        plan2,fp2=m.preview(c,e['project']);assert not plan2['removed'];m.execute(c,e['project'],e['admin'],fp2)
        assert c.execute('SELECT jsonb_agg(to_jsonb(x) ORDER BY id) FROM coll_pat.catalog x WHERE project_id=%s',(other['project'],)).fetchone()[0]==before


def test_v2_cannot_downgrade_existing_draft_contract(e21):
    op=modern(e21,draft=True);save(e21,op)
    next_op=copy.deepcopy(op);next_op['operation_id']=uid();next_op['payload']['expected_revision']=1;next_op['payload'].pop('gps_contract')
    with pytest.raises(psycopg.Error,match='Contratto GPS v2'):save(e21,next_op)
