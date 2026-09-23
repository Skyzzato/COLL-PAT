"""One-time, project-scoped purge. Preview by default; never loaded by schema migrations.
Requires an explicitly supplied COLL_PAT_MAINTENANCE_DSN with owner privileges.
Storage objects are removed via the Storage API by the app/admin worker, not SQL DML.
"""
import argparse
import hashlib
import json
import os
import uuid
import psycopg
from psycopg.types.json import Jsonb

PROJECT='00000000-0000-4000-8000-000000000013'

def preview(connection,project):
    if project!=PROJECT:raise ValueError('Only the named COLL-PAT project is permitted')
    row=connection.execute('SELECT name FROM coll_pat.projects WHERE id=%s',(project,)).fetchone()
    if not row or row[0]!='COLL-PAT':raise ValueError('COLL-PAT project identity mismatch')
    roots=connection.execute("SELECT id FROM coll_pat.catalog WHERE project_id=%s AND kind='collector' AND coalesce((data->>'archived')::boolean,false) UNION SELECT id FROM coll_pat.catalog_deletions WHERE project_id=%s AND kind='collector' ORDER BY id",(project,project)).fetchall()
    scopes=[connection.execute('SELECT coll_pat.deletion_scope(%s,%s,%s)',(project,'collector',r[0])).fetchone()[0] for r in roots]
    removed=set();shared=set();inspections=set();photos=set()
    for scope in scopes:
        removed.update(scope['removed']);shared.update(scope['shared']);inspections.update(scope['inspections']);photos.update(scope['photos'])
    root_ids=[str(r[0]) for r in roots]
    # A point shared only by archived collectors is also exclusively archived.
    for (ident,) in connection.execute("SELECT id FROM coll_pat.catalog c WHERE project_id=%s AND kind<>'collector' AND jsonb_array_length(coalesce(data->'collectors','[]'))>0 AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements_text(data->'collectors') membership WHERE NOT membership=ANY(%s::text[]))",(project,root_ids)):
        removed.add(str(ident))
    legacy=connection.execute('SELECT id,kind FROM coll_pat.catalog_deletions WHERE project_id=%s ORDER BY id',(project,)).fetchall()
    for ident,kind in legacy:
        # Surviving shared assets with an active parent are never purged by archival maintenance.
        active=connection.execute("SELECT EXISTS(SELECT 1 FROM coll_pat.catalog c JOIN coll_pat.catalog parent ON parent.project_id=c.project_id AND c.data->'collectors' ? parent.id::text WHERE c.project_id=%s AND c.id=%s AND parent.kind='collector' AND NOT coalesce((parent.data->>'archived')::boolean,false) AND NOT EXISTS(SELECT 1 FROM coll_pat.catalog_deletions d WHERE d.project_id=parent.project_id AND d.id=parent.id))",(project,ident)).fetchone()[0]
        if kind!='collector' and active:continue
        removed.add(str(ident))
    for (ident,) in connection.execute("SELECT id FROM coll_pat.inspections WHERE project_id=%s AND (cancelled OR manhole_id=ANY(%s::uuid[])) ORDER BY id",(project,list(removed))):inspections.add(str(ident))
    for (ident,) in connection.execute('SELECT id FROM coll_pat.inspection_photos WHERE project_id=%s AND inspection_id=ANY(%s::uuid[]) ORDER BY id',(project,list(inspections))):photos.add(str(ident))
    plan=dict(project=project,scopes=scopes,removed=sorted(removed),shared=sorted(shared-removed),inspections=sorted(inspections),photos=sorted(photos),legacy=[str(r[0]) for r in legacy])
    # Include revisions/content digests so concurrent changes invalidate a previously reviewed plan.
    plan['state']=connection.execute("SELECT coll_pat.operation_hash(jsonb_build_object('catalog',(SELECT jsonb_agg(to_jsonb(c) ORDER BY id) FROM coll_pat.catalog c WHERE project_id=%s),'inspections',(SELECT jsonb_agg(jsonb_build_array(id,revision,cancelled) ORDER BY id) FROM coll_pat.inspections WHERE project_id=%s),'deletions',(SELECT jsonb_agg(to_jsonb(d) ORDER BY id) FROM coll_pat.catalog_deletions d WHERE project_id=%s)))",(project,project,project)).fetchone()[0]
    fingerprint=hashlib.sha256(json.dumps(plan,sort_keys=True).encode()).hexdigest()
    return plan,fingerprint

def execute(connection,project,actor,expected):
    connection.execute('SELECT 1 FROM coll_pat.projects WHERE id=%s FOR UPDATE',(project,))
    if not connection.execute("SELECT 1 FROM coll_pat.memberships WHERE project_id=%s AND user_id=%s AND role='admin'",(project,actor)).fetchone():raise ValueError('A project administrator actor is required')
    plan,fingerprint=preview(connection,project)
    if expected!=fingerprint:raise ValueError('Preview changed: inspect the new counts before execution')
    operation=str(uuid.uuid4())
    # Remove each archived membership independently; preserve memberships in other collectors.
    for scope in plan['scopes']:connection.execute('SELECT coll_pat.purge_scope(%s,%s,%s,%s)',(project,Jsonb(scope),operation,actor))
    union=dict(id='',removed=plan['removed'],shared=[],inspections=plan['inspections'],photos=plan['photos'])
    connection.execute('SELECT coll_pat.purge_scope(%s,%s,%s,%s)',(project,Jsonb(union),operation,actor))
    connection.execute("INSERT INTO coll_pat.object_deletions(project_id,id,kind) SELECT project_id,id,kind FROM coll_pat.catalog_deletions WHERE project_id=%s AND id=ANY(%s::uuid[]) ON CONFLICT DO NOTHING",(project,plan['removed']))
    connection.execute("UPDATE coll_pat.catalog_deletions SET original='{}',source_identity=NULL WHERE project_id=%s",(project,))
    remaining=connection.execute('SELECT count(*) FROM coll_pat.storage_deletions WHERE project_id=%s AND confirmed_at IS NULL',(project,)).fetchone()[0]
    return dict(database_purge_executed=True,removed_catalog=len(plan['removed']),removed_inspections=len(plan['inspections']),storage_pending=remaining,storage_complete=remaining==0)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--project',required=True);parser.add_argument('--apply',action='store_true');parser.add_argument('--actor');parser.add_argument('--expected-fingerprint');args=parser.parse_args()
    with psycopg.connect(os.environ['COLL_PAT_MAINTENANCE_DSN'],connect_timeout=10) as connection:
        connection.execute('SET statement_timeout=60000')
        if args.apply:
            if not args.actor or not args.expected_fingerprint:parser.error('--apply requires --actor and --expected-fingerprint')
            result=execute(connection,args.project,args.actor,args.expected_fingerprint)
        else:
            connection.execute('SET TRANSACTION READ ONLY')
            plan,fp=preview(connection,args.project);result={k:len(plan[k]) for k in ['removed','shared','inspections','photos','legacy']};result.update(project=args.project,fingerprint=fp,writes=False)
        print(json.dumps(result,indent=2))
if __name__=='__main__':main()
