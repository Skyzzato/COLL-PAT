"""Explicit private legacy-area → v0.13 project catalogue migration.

Retains asset UUIDs; never touches users, credentials or inspection history.
Requires owner COLL_PAT_ADMIN_DSN plus an already authorized Auth administrator.
"""
import argparse,os,uuid,json
import psycopg
from psycopg.types.json import Jsonb

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--area',required=True);p.add_argument('--project',required=True);p.add_argument('--auth-admin',required=True);p.add_argument('--execute',action='store_true');a=p.parse_args()
    with psycopg.connect(os.environ['COLL_PAT_ADMIN_DSN']) as c:
        rows=c.execute('SELECT id,code FROM collettori.collectors WHERE area_id=%s',(a.area,)).fetchall();codes={code:ident for ident,code in rows}
        dataset=c.execute('SELECT payload FROM collettori.datasets WHERE area_id=%s ORDER BY created_at DESC LIMIT 1',(a.area,)).fetchone();assert dataset,'Legacy dataset not found'
        data=dataset[0];items=[]
        def item(kind,p):items.append(dict(kind=kind,data=p))
        for ident,code in rows:item('collector',dict(id=ident,code=code,description=code+' — descrizione legacy da verificare',type='CV',visits_h1=2,visits_h2=2,hours_km_visit=2,length_m=None,length_source='UNAVAILABLE',length_complete=False,archived=False,synthetic=data.get('synthetic',False)))
        for point in data['points']:
            point=dict(point);point['collectors']=[codes.get(v,v) for v in point['collectors']];point.setdefault('description','');point.setdefault('asset_type','UNKNOWN');item('point',point)
        for segment in data['segments']:
            s=dict(segment);s['collectors']=[codes.get(s.get('collector'),s.get('collector_id'))];s.setdefault('schematic',False);item('segment',s)
        print(json.dumps({'area':a.area,'project':a.project,'counts':{k:sum(i['kind']==k for i in items) for k in ['collector','point','segment']},'execute':a.execute}))
        if not a.execute:c.rollback();return
        c.execute('SET LOCAL ROLE authenticated');c.execute("SELECT set_config('request.jwt.claim.sub',%s,true)",(a.auth_admin,))
        state=c.execute('SELECT public.coll_pat_status(%s)',(a.project,)).fetchone()[0]
        operation=dict(operation_id=str(uuid.uuid4()),project_id=a.project,generation=state['generation'],payload_version=2,kind='catalog',payload=dict(items=items,provenance=None))
        c.execute('SELECT public.coll_pat_apply(%s)',(Jsonb(operation),));print('Catalogue migration committed with original UUIDs')
if __name__=='__main__':main()
