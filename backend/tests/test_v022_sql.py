"""v0.22: actual restricted PostgreSQL roles in disposable local databases."""
import copy
from concurrent.futures import ThreadPoolExecutor
import psycopg
import pytest
from psycopg.types.json import Jsonb
from test_v013_sql import ROOT, database, env as old_env, rpc, uid
from test_v014_sql import db14, save
from test_v015_sql import db15
from test_v02_sql import db02, apply_catalog
from test_v021_sql import db21, modern, patch, deletion, execute

@pytest.fixture(scope='module')
def db22(db21):
    with psycopg.connect(db21,autocommit=True) as c:
        c.execute("ALTER TABLE auth.users ADD COLUMN email text, ADD COLUMN raw_user_meta_data jsonb DEFAULT '{}'::jsonb")
        for _ in range(2):c.execute((ROOT/'supabase/migrations/202609240010_coll_pat_v022.sql').read_text(encoding='utf-8'))
    return db21

@pytest.fixture
def e22(db22):
    e=old_env.__wrapped__(db22);e['viewer']=uid()
    with psycopg.connect(db22) as c:
        c.execute('INSERT INTO auth.users(id,email) VALUES(%s,%s)',(e['viewer'],'viewer@example.invalid'))
        c.execute('INSERT INTO coll_pat.memberships VALUES(%s,%s,%s)',(e['project'],e['viewer'],'viewer'))
    return e

def change(e,user,role,previous,actor=None,enabled=True):
    return rpc(e['dsn'],actor or e['admin'],'coll_pat_set_role',p_project=e['project'],p_user=user,p_role=role,p_expected=previous,p_enabled=enabled)

def test_viewer_reads_and_all_write_entrypoints_deny(e22):
    e=e22;v=e['viewer'];op=modern(e,draft=True)
    for name in ['coll_pat_catalog','coll_pat_history','coll_pat_inspection_summary','coll_pat_export_history','coll_pat_status']:
        assert isinstance(rpc(e['dsn'],v,name,p_project=e['project']),dict)
    for name in ['coll_pat_apply','coll_pat_save_inspection','coll_pat_patch_object','coll_pat_delete_permanent']:
        with pytest.raises(psycopg.Error,match='visualizzatore'):rpc(e['dsn'],v,name,operation=op)
    for name,params in [('coll_pat_photo_uploaded',dict(p_inspection=uid(),p_id=uid())),('coll_pat_storage_confirm',dict(p_photo=uid()))]:
        with pytest.raises(psycopg.Error,match='visualizzatore'):rpc(e['dsn'],v,name,p_project=e['project'],**params)
    with pytest.raises(psycopg.Error):rpc(e['dsn'],v,'coll_pat_users',p_project=e['project'])
    with pytest.raises(psycopg.Error):change(e,v,'admin','viewer',v)

def test_operator_cannot_delete_points_or_manage_users(e22):
    e=e22
    with pytest.raises(psycopg.Error):deletion(e,'point',e['point']['id'],e['user'])
    op=deletion(e,'point',e['point']['id'])
    with pytest.raises(psycopg.Error):execute(e,op,e['user'])
    with pytest.raises(psycopg.Error):change(e,e['user'],'admin','inspector',e['user'])
    patch(e,e['point']['id'],{'under_asphalt':True},{'under_asphalt':e['point'].get('under_asphalt')},e['user'])

def test_revoked_operator_cannot_replay_receipt_or_pending(e22):
    e=e22;op=modern(e,draft=True);save(e,op)
    change(e,e['user'],'viewer','inspector')
    with pytest.raises(psycopg.Error):save(e,op)
    op['operation_id']=uid()
    with pytest.raises(psycopg.Error):save(e,op)
    with pytest.raises(psycopg.Error):patch(e,e['point']['id'],{'under_asphalt':True},{'under_asphalt':False},e['user'])

def test_users_scope_registration_pagination_and_audit(e22):
    e=e22;foreign=uid();pending=uid()
    with psycopg.connect(e['dsn']) as c:
        c.execute('INSERT INTO auth.users VALUES(%s,%s,%s)',(foreign,'foreign@example.invalid',Jsonb({'application':'OTHER'})))
        c.execute('INSERT INTO auth.users VALUES(%s,%s,%s)',(pending,'pending@example.invalid',Jsonb({'application':'COLL-PAT','coll_pat_project':e['project'],'full_name':'Pending Test'})))
    page=rpc(e['dsn'],e['admin'],'coll_pat_users',p_project=e['project'])
    assert foreign not in [r['id'] for r in page['items']]
    assert next(r for r in page['items'] if r['id']==pending)['state']=='PENDING'
    assert change(e,pending,'viewer',None)['role']=='viewer'
    with psycopg.connect(e['dsn']) as c:
        audit=c.execute('SELECT actor,target,previous_role,new_role FROM coll_pat.role_audit WHERE project_id=%s',(e['project'],)).fetchone()
        assert tuple(map(str,audit))==(e['admin'],pending,'None','viewer')
    with pytest.raises(psycopg.Error):change(e,foreign,'admin',None)
    with pytest.raises(psycopg.Error):change(e,pending,'inspector',None)

def test_last_admin_concurrency_and_disable(e22):
    e=e22
    with pytest.raises(psycopg.Error,match='Ultimo amministratore'):change(e,e['admin'],'viewer','admin')
    change(e,e['other'],'admin','inspector')
    def demote(actor):
        try:return change(e,actor,'viewer','admin',actor)
        except psycopg.Error as err:return err.sqlstate
    with ThreadPoolExecutor(2) as pool:results=list(pool.map(demote,[e['admin'],e['other']]))
    assert sum(isinstance(r,dict) for r in results)==1 and 'PT409' in results
    with psycopg.connect(e['dsn']) as c:assert c.execute("SELECT count(*) FROM coll_pat.memberships WHERE project_id=%s AND role='admin'",(e['project'],)).fetchone()[0]==1

def test_export_more_than_one_page_bounds_and_revision(e22):
    e=e22;op=modern(e);save(e,op)
    with psycopg.connect(e['dsn']) as c:
        # Duplicate a valid test row with fresh IDs in this disposable database to exercise pagination.
        row=c.execute('SELECT to_jsonb(i) FROM coll_pat.inspections i WHERE project_id=%s',(e['project'],)).fetchone()[0]
        for n in range(205):
            clone=copy.deepcopy(row);clone['id']=uid();clone['original']['id']=clone['id']
            c.execute('INSERT INTO coll_pat.inspections SELECT * FROM jsonb_populate_record(NULL::coll_pat.inspections,%s)',(Jsonb(clone),))
    page=rpc(e['dsn'],e['viewer'],'coll_pat_export_history',p_project=e['project'])
    assert len(page['items'])==200 and page['next']
    tail=rpc(e['dsn'],e['viewer'],'coll_pat_export_history',p_project=e['project'],p_after=page['next'],p_snapshot=page['snapshot'])
    assert len(tail['items'])==6 and not tail['next']
    assert not set(r['id'] for r in page['items'])&set(r['id'] for r in tail['items'])
    empty=rpc(e['dsn'],e['viewer'],'coll_pat_export_history',p_project=e['project'],p_from='2027-01-01T00:00:00Z')
    assert empty['items']==[]
    save(e,modern(e,draft=True))
    with pytest.raises(psycopg.Error,match='Storico cambiato'):rpc(e['dsn'],e['viewer'],'coll_pat_export_history',p_project=e['project'],p_snapshot=page['snapshot'])

def test_anonymous_and_private_schema_denied(e22):
    with psycopg.connect(e22['dsn']) as c:
        assert c.execute("SELECT bool_and(relrowsecurity) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='coll_pat' AND c.relkind='r'").fetchone()[0]
        assert not c.execute("SELECT has_function_privilege('anon','public.coll_pat_users(uuid,text,uuid)','execute')").fetchone()[0]
        assert not c.execute("SELECT has_function_privilege('authenticated','coll_pat.coll_pat_save_inspection_v021(jsonb)','execute')").fetchone()[0]
        assert not c.execute("SELECT has_table_privilege('authenticated','auth.users','select')").fetchone()[0]

def test_user_pages_and_reset_rights(e22):
    e=e22
    with psycopg.connect(e['dsn']) as c:
        for index in range(53):
            c.execute('INSERT INTO auth.users VALUES(%s,%s,%s)',(uid(),f'page-{index:03}@example.invalid',Jsonb({'application':'COLL-PAT','coll_pat_project':e['project']})))
    first=rpc(e['dsn'],e['admin'],'coll_pat_users',p_project=e['project'],p_query='page-')
    second=rpc(e['dsn'],e['admin'],'coll_pat_users',p_project=e['project'],p_query='page-',p_after=first['next'])
    assert len(first['items'])==50 and len(second['items'])==3 and second['next'] is None
    assert len({r['id'] for r in first['items']+second['items']})==53
    for actor in [e['viewer'],e['user']]:
        with pytest.raises(psycopg.Error):rpc(e['dsn'],actor,'coll_pat_backup',p_project=e['project'])
        with pytest.raises(psycopg.Error):rpc(e['dsn'],actor,'coll_pat_reset',p_project=e['project'],p_generation=0,p_confirmation='AZZERA',p_backup_token=uid())
    backup=rpc(e['dsn'],e['admin'],'coll_pat_backup',p_project=e['project'])
    assert backup['backup_token']
