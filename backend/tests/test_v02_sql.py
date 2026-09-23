"""v0.2 lifecycle on disposable PostgreSQL/PostGIS; no real infrastructure fixtures."""
import copy
import pytest
import psycopg
from test_v013_sql import ROOT, database, env as old_env, rpc, uid
from test_v014_sql import db14, save
from test_v015_sql import db15, averaged


@pytest.fixture(scope='module')
def db02(db15):
    with psycopg.connect(db15, autocommit=True) as c:
        for _ in range(2):
            c.execute((ROOT/'supabase/migrations/202609230007_coll_pat_v02.sql').read_text(encoding='utf-8'))
    return db15


@pytest.fixture
def e02(db02):
    return old_env.__wrapped__(db02)


def delete(e, cid=None):
    return dict(operation_id=uid(), project_id=e['project'], generation=0, payload_version=2,
                kind='catalog_delete', app_version='0.2', payload=dict(id=cid or e['point']['collectors'][0], confirmed=True))


def apply_catalog(e, items):
    op=dict(operation_id=uid(), project_id=e['project'], generation=0, payload_version=2,
            kind='catalog', app_version='0.2', payload=dict(items=items))
    return rpc(e['dsn'], e['admin'], 'coll_pat_apply', operation=op)


def test_v02_is_newer_than_all_previous_publications(db02):
    with psycopg.connect(db02) as c:
        for version in ['0.1', '0.11', '0.12', '0.13', '0.14', '0.15', '0.16']:
            assert c.execute("SELECT coll_pat.version_parts('0.2')>coll_pat.version_parts(%s)", (version,)).fetchone()[0]
        c.execute("SET LOCAL ROLE authenticated")
        assert c.execute("SELECT public.coll_pat_version()").fetchone()[0]['minimum_supported_version']=='0.14'


def test_delete_requires_admin_and_explicit_confirmation(e02):
    e=e02;op=delete(e)
    with pytest.raises(psycopg.Error, match='permission denied'):
        rpc(e['dsn'],e['user'],'coll_pat_delete_collector',operation=op)
    op['payload']['confirmed']=False
    with pytest.raises(psycopg.Error, match='Conferma'):
        rpc(e['dsn'],e['admin'],'coll_pat_delete_collector',operation=op)


def test_empty_server_collector_physically_deleted_and_retry_safe(e02):
    e=e02;c=copy.deepcopy(e['seed']['collectors'][0]);c.update(id=uid(), code='NEW-SYNTHETIC')
    apply_catalog(e,[dict(kind='collector',data=c)])
    op=delete(e,c['id']);receipt=rpc(e['dsn'],e['admin'],'coll_pat_delete_collector',operation=op)
    assert rpc(e['dsn'],e['admin'],'coll_pat_delete_collector',operation=op)==receipt
    with psycopg.connect(e['dsn']) as db:
        assert db.execute('SELECT count(*) FROM coll_pat.catalog WHERE project_id=%s AND id=%s',(e['project'],c['id'])).fetchone()[0]==0
    with pytest.raises(psycopg.Error,match='eliminato'):
        apply_catalog(e,[dict(kind='collector',data=c)])
    page=rpc(e['dsn'],e['user'],'coll_pat_catalog',p_project=e['project'])
    assert c['id'] in [d['id'] for d in page['deleted']]
    assert c['id'] not in [item['data']['id'] for item in page['items']+page['archived']]


def test_history_and_photos_preserved_but_deleted_collector_never_returns(e02):
    e=e02;inspection=averaged(e,draft=True);photo=uid()
    inspection['payload']['photos']=[dict(id=photo,created_at='2026-09-23T10:00:00Z')]
    save(e,inspection)
    op=delete(e);rpc(e['dsn'],e['admin'],'coll_pat_delete_collector',operation=op)
    page=rpc(e['dsn'],e['user'],'coll_pat_catalog',p_project=e['project'])
    assert op['payload']['id'] not in [i['data']['id'] for i in page['items']+page['archived']]
    with psycopg.connect(e['dsn']) as db:
        assert db.execute('SELECT count(*) FROM coll_pat.inspections WHERE project_id=%s',(e['project'],)).fetchone()[0]==1
        assert db.execute('SELECT count(*) FROM coll_pat.inspection_photos WHERE project_id=%s AND id=%s',(e['project'],photo)).fetchone()[0]==1
    # A later unrelated catalogue change must not modify tombstoned historical parents.
    c=copy.deepcopy(e['seed']['collectors'][-1]);c['description']='Still editable'
    apply_catalog(e,[dict(kind='collector',data=c)])


def test_manual_points_can_reference_a_point_later_in_same_atomic_batch(e02):
    e=e02;c=copy.deepcopy(e['seed']['collectors'][0]);c.update(id=uid(),code='MANUAL-SYNTHETIC')
    p=copy.deepcopy(e['point']);p.update(id=uid(),collectors=[c['id']],code='0001')
    q=copy.deepcopy(p);q.update(id=uid(),code='0002');p['next_ids']=[q['id']]
    apply_catalog(e,[dict(kind='collector',data=c),dict(kind='point',data=p),dict(kind='point',data=q)])
    p['next_ids']=[uid()]
    with pytest.raises(psycopg.Error,match='Successivo'):
        apply_catalog(e,[dict(kind='point',data=p)])


def test_shared_points_survive_in_the_other_collector(e02):
    e=e02;first=e['seed']['collectors'][0]['id'];other=e['seed']['collectors'][-1]['id']
    p=copy.deepcopy(e['point']);p['collectors']=[first,other]
    apply_catalog(e,[dict(kind='point',data=p)])
    rpc(e['dsn'],e['admin'],'coll_pat_delete_collector',operation=delete(e,first))
    page=rpc(e['dsn'],e['user'],'coll_pat_catalog',p_project=e['project'])
    point=next(i['data'] for i in page['items'] if i['data']['id']==p['id'])
    assert point['collectors']==[other]
