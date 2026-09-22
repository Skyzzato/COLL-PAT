"""Private, scoped, one-time cleanup. Dry run unless --execute --confirmation AZZERA.

Requires COLL_PAT_ADMIN_DSN in the environment (never stored or printed).
Run only after owner-reviewed v0.13 migrations and Auth bootstrap. Does not guess an area.
"""
import argparse,json,os,uuid
from datetime import datetime,timezone
from pathlib import Path
import psycopg
from psycopg.types.json import Jsonb

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--project',required=True);p.add_argument('--area',required=True);p.add_argument('--auth-admin',required=True)
    p.add_argument('--backup',type=Path,required=True);p.add_argument('--execute',action='store_true');p.add_argument('--confirmation',default='')
    a=p.parse_args();uuid.UUID(a.project);uuid.UUID(a.auth_admin)
    if a.execute and a.confirmation!='AZZERA':p.error('Execution requires --confirmation AZZERA')
    with psycopg.connect(os.environ['COLL_PAT_ADMIN_DSN']) as c:
        project=c.execute('SELECT development,generation FROM coll_pat.projects WHERE id=%s FOR UPDATE',(a.project,)).fetchone()
        assert project and project[0], 'Explicit development project required'
        assert c.execute("SELECT 1 FROM coll_pat.memberships WHERE project_id=%s AND user_id=%s AND role='admin'",(a.project,a.auth_admin)).fetchone(), 'Verified Auth administrator required'
        assert c.execute('SELECT synthetic FROM collettori.areas WHERE id=%s',(a.area,)).fetchone()==(True,), 'Explicit synthetic legacy area required'
        assert not c.execute('SELECT 1 FROM coll_pat.inspections WHERE project_id=%s LIMIT 1',(a.project,)).fetchone(), 'New-protocol inspections exist: use dashboard backup/reset separately first'
        assert not c.execute("SELECT 1 FROM coll_pat.reset_log WHERE project_id=%s AND previous_generation=-1",(a.project,)).fetchone(), 'Initial legacy cleanup already recorded'
        # Lock exactly the app legacy tables; old runtime inspection grants are already revoked.
        c.execute('LOCK TABLE collettori.inspections,collettori.revisions,collettori.location_events,collettori.anomalies,collettori.sync_operations,collettori.audit IN SHARE ROW EXCLUSIVE MODE')
        rows=c.execute('SELECT id,to_jsonb(i) FROM collettori.inspections i WHERE area_id=%s AND synthetic=true',(a.area,)).fetchall()
        ids=[r[0] for r in rows];backup={'project':a.project,'area':a.area,'at':datetime.now(timezone.utc).isoformat(),'inspections':[r[1] for r in rows]}
        for table in ['revisions','location_events','anomalies']:
            backup[table]=[r[0] for r in c.execute(f'SELECT to_jsonb(t) FROM collettori.{table} t WHERE inspection_id=ANY(%s)',(ids,))]
        backup['sync_operations']=[r[0] for r in c.execute("SELECT to_jsonb(t) FROM collettori.sync_operations t WHERE receipt->>'inspection_id'=ANY(%s)",(ids,))]
        backup['audit']=[r[0] for r in c.execute('SELECT to_jsonb(t) FROM collettori.audit t WHERE target_id=ANY(%s)',(ids,))]
        print(json.dumps({'development_project':a.project,'synthetic_area':a.area,'inspection_count':len(ids),'execute':a.execute}))
        if not a.execute:c.rollback();return
        # Exclusive create, flush before deleting. Contains no users/password/session tables.
        a.backup.parent.mkdir(parents=True,exist_ok=True)
        with a.backup.open('x',encoding='utf-8') as f:json.dump(backup,f,ensure_ascii=False,default=str,indent=2);f.flush();os.fsync(f.fileno())
        for table in ['anomalies','location_events','revisions']:c.execute(f'DELETE FROM collettori.{table} WHERE inspection_id=ANY(%s)',(ids,))
        c.execute("DELETE FROM collettori.sync_operations WHERE receipt->>'inspection_id'=ANY(%s)",(ids,))
        c.execute('DELETE FROM collettori.audit WHERE target_id=ANY(%s)',(ids,))
        c.execute('DELETE FROM collettori.inspections WHERE id=ANY(%s)',(ids,))
        c.execute('UPDATE coll_pat.projects SET generation=generation+1 WHERE id=%s',(a.project,))
        c.execute('INSERT INTO coll_pat.reset_log(project_id,author,previous_generation,generation,inspection_count) VALUES(%s,%s,-1,%s,%s)',(a.project,a.auth_admin,project[1]+1,len(ids)))
    print('Initial scoped cleanup committed; catalogue, accounts and configuration preserved.')
if __name__=='__main__':main()
