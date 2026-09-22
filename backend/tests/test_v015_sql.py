"""v0.15 contract tested against local PostgreSQL/PostGIS, never the production service."""
import math
import pytest
import psycopg
from test_v013_sql import database, env as old_env, ROOT
from test_v014_sql import db14, operation, save, load

@pytest.fixture(scope='module')
def db15(db14):
    with psycopg.connect(db14, autocommit=True) as c:
        for _ in range(2):
            c.execute((ROOT/'supabase/migrations/202609220005_coll_pat_v015.sql').read_text(encoding='utf-8'))
        assert c.execute('SELECT latest_version,minimum_supported_version FROM coll_pat.app_config').fetchone()==('0.14','0.14')
    return db14

@pytest.fixture
def e15(db15): return old_env.__wrapped__(db15)

def averaged(e, *, accuracy=5, distance=0, exception=None, draft=False):
    op=operation(e);op['app_version']='0.15'
    p=op['payload'];p['app_version']='0.15'
    if not draft: p.update(status='COMPLETO',completed_at='2026-01-01T09:01:00Z')
    event=p['events'][0]
    event.update(method='SPHERICAL_MEAN_MAX_ACCURACY_V1',sample_count=5,duration_ms=5000,sample_span_ms=4000,
                 started_elapsed_ns=1000000000,ended_elapsed_ns=6000000000,last_sample_elapsed_ns=5500000000,
                 acquisition_started_at='2026-01-01T09:00:00Z',acquisition_ended_at='2026-01-01T09:00:05Z',
                 dispersion_m=1,age_s=.5,accuracy_m=accuracy,mock=False,
                 match_outcome='EXCEPTION' if exception is not None else 'VERIFIED')
    event['latitude']+=math.degrees(distance/6371008.8)
    if exception is not None: event['exception_reason']=exception;p['sheet']['exception_reason']=exception
    return op

def test_valid_average_preserved_with_server_receipt(e15):
    op=averaged(e15);r=save(e15,op);assert r['server_gps']['gps_reliability']=='RELIABLE'
    assert save(e15,op)==r
    event=load(e15,op['payload']['id'])['original']['events'][0]
    assert event['sample_count']==5 and event['dispersion_m']==1 and event['method']=='SPHERICAL_MEAN_MAX_ACCURACY_V1'

def test_distance_exception_retains_accurate_measure_and_reason(e15):
    op=averaged(e15,distance=50,exception='Accesso dal lato opposto')
    result=save(e15,op);assert result['server_gps']['gps_reliability']=='MATCH_EXCEPTION'
    assert result['server_gps']['distance_m']>49
    assert load(e15,op['payload']['id'])['original']['events'][0]['exception_reason']=='Accesso dal lato opposto'

@pytest.mark.parametrize('draft',[True,False])
@pytest.mark.parametrize('accuracy',[10.1,None,float('inf')])
def test_accuracy_cannot_be_overridden_even_in_draft(e15,draft,accuracy):
    op=averaged(e15,accuracy=accuracy,exception='Tentata deroga',draft=draft)
    if accuracy==float('inf'):op['payload']['events'][0]['accuracy_m']='Infinity'
    with pytest.raises(psycopg.Error,match='Accuratezza GPS'):save(e15,op)

def test_equality_is_allowed_but_uncertain_match_needs_reason(e15):
    assert save(e15,averaged(e15,accuracy=10))['server_gps']['gps_reliability']=='RELIABLE'
    with pytest.raises(psycopg.Error,match='Corrispondenza GPS'):save(e15,averaged(e15,distance=12))
    assert save(e15,averaged(e15,distance=12,exception='Verifica visiva del codice'))['server_gps']['gps_reliability']=='MATCH_EXCEPTION'

@pytest.mark.parametrize('reason',['','   '])
def test_blank_exception_rejected(e15,reason):
    with pytest.raises(psycopg.Error,match='Motivazione Eccezione'):save(e15,averaged(e15,distance=50,exception=reason))

def test_insufficient_samples_and_missing_timestamps_rejected(e15):
    op=averaged(e15);op['payload']['events'][0]['sample_count']=1
    with pytest.raises(psycopg.Error,match='incompleta'):save(e15,op)
    op=averaged(e15);del op['payload']['events'][0]['acquisition_started_at']
    with pytest.raises(psycopg.Error,match='incompleta'):save(e15,op)

def test_impediment_does_not_invent_failed_location_event(e15):
    op=averaged(e15);p=op['payload'];p.update(status='IMPEDITO',events=[])
    p['sheet'].update(impediment_reason='Localizzazione non disponibile e accesso impedito',opened=False)
    for key in ['deposits','flow','walls','damage']:p['sheet'][key]='NON_OSSERVABILE'
    save(e15,op);assert load(e15,p['id'])['original']['events']==[]

def test_draft_conflict_keeps_first_server_revision(e15):
    a=averaged(e15,draft=True);save(e15,a)
    b=averaged(e15,draft=True);b['payload']['id']=a['payload']['id'];b['payload']['events'][0]['inspection_id']=a['payload']['id']
    with pytest.raises(psycopg.Error,match='Bozza aggiornata'):save(e15,b)
    assert load(e15,a['payload']['id'])['revision']==1

def test_existing_shared_draft_accepts_notes_without_new_gps(e15):
    op=averaged(e15,draft=True);save(e15,op)
    row=load(e15,op['payload']['id'])
    with psycopg.connect(e15['dsn']) as c:
        c.execute("UPDATE coll_pat.catalog SET data=jsonb_set(data,'{uncertainty_m}','null'::jsonb) WHERE project_id=%s AND id=%s",(e15['project'],e15['point']['id']))
    follow=operation(e15,1,row['original']);follow['payload']['sheet']['notes']='No new acquisition for draft notes'
    assert save(e15,follow)['revision']==2
