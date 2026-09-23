"""v0.16 synthetic catalogue seed is additive and idempotent on local PostgreSQL."""
import pytest
import psycopg
from test_v015_sql import db15
from test_v014_sql import db14
from test_v013_sql import ROOT, database

PROJECT = '00000000-0000-4000-8000-000000000013'
ADMIN = '00000000-0000-4000-8000-000000000001'

@pytest.fixture(scope='module')
def db16(db15):
    with psycopg.connect(db15, autocommit=True) as c:
        c.execute('INSERT INTO auth.users(id) VALUES(%s) ON CONFLICT DO NOTHING', (ADMIN,))
        c.execute('INSERT INTO coll_pat.projects(id,name) VALUES(%s,%s) ON CONFLICT DO NOTHING', (PROJECT,'COLL-PAT'))
        c.execute("INSERT INTO coll_pat.memberships(project_id,user_id,role) VALUES(%s,%s,'inspector') ON CONFLICT DO NOTHING", (PROJECT,ADMIN))
        sql = (ROOT/'supabase/migrations/202609230006_coll_pat_v016_server_seed.sql').read_text(encoding='utf-8')
        c.execute(sql)
        revision = c.execute('SELECT catalog_revision FROM coll_pat.projects WHERE id=%s', (PROJECT,)).fetchone()[0]
        c.execute(sql)
        assert c.execute('SELECT catalog_revision FROM coll_pat.projects WHERE id=%s', (PROJECT,)).fetchone()[0] == revision
        assert c.execute('SELECT role FROM coll_pat.memberships WHERE project_id=%s AND user_id=%s', (PROJECT,ADMIN)).fetchone()[0] == 'inspector'
    return db15

def test_server_seed_has_complete_stable_synthetic_catalogue(db16):
    with psycopg.connect(db16) as c:
        counts = c.execute("SELECT kind,count(*) FROM coll_pat.catalog WHERE project_id=%s AND data->>'server_seed'='v0.16' GROUP BY kind ORDER BY kind", (PROJECT,)).fetchall()
        assert counts == [('collector',3),('point',22),('segment',19)]
        assert c.execute("SELECT bool_and((data->>'synthetic')::boolean) FROM coll_pat.catalog WHERE project_id=%s AND data->>'server_seed'='v0.16'", (PROJECT,)).fetchone()[0]
