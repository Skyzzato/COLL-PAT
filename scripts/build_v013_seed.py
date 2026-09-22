"""Deterministic, synthetic seed. Preserves all historical Trento asset UUIDs."""
import json, math, uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
NS=uuid.UUID('849b60f6-51c0-45be-995e-85f4266eb613')
def uid(value): return str(uuid.uuid5(NS,value))
def distance(a,b):
    p,q=map(math.radians,(a[1],b[1])); h=math.sin((q-p)/2)**2+math.cos(p)*math.cos(q)*math.sin(math.radians(b[0]-a[0])/2)**2
    return 12742017.6*math.asin(math.sqrt(h))
def main():
    p=json.loads((ROOT/'demo/trento-v0.11.json').read_text(encoding='utf-8'))
    p.update(version='00000000-0000-4000-8000-000000000013',name='COLL-PAT · dati sintetici Trento e Lavis',collectors=[],osm=True)
    for town,number in [('Trento',1),('Lavis',2)]:
        cid=uid('collector:'+town)
        if town=='Trento':
            points=p['points'];segments=p['segments']
        else:
            points=[dict(id=uid(f'Lavis:point:{i}'),code=f'LV-{i+1:03}',latitude=46.133+i*.00075,longitude=11.109+i*.00022,uncertainty_m=2) for i in range(6)]
            segments=[dict(id=uid(f'Lavis:segment:{i}'),from_id=a['id'],to_id=b['id'],geometry={'type':'LineString','coordinates':[[a['longitude'],a['latitude']],[b['longitude'],b['latitude']]]}) for i,(a,b) in enumerate(zip(points,points[1:]))]
        chainage=0
        for i,point in enumerate(points):
            point.update(collectors=[cid],collector_id=cid,description=f'Manufatto sintetico {town}',asset_type='MANHOLE',synthetic=True,branch='SINTETICO-1',sequence=i,origin_id=points[0]['id'])
            if i:
                seg=segments[i-1];length=sum(distance(a,b) for a,b in zip(seg['geometry']['coordinates'],seg['geometry']['coordinates'][1:]));chainage+=length
                point.update(previous_id=points[i-1]['id'],previous_distance_m=round(length,3))
            point.update(chainage_m=round(chainage,3),chainage_source='CALCULATED_SCHEMATIC')
        for s in segments:s.update(collectors=[cid],collector_id=cid,synthetic=True,schematic=True,length_m=sum(distance(a,b) for a,b in zip(s['geometry']['coordinates'],s['geometry']['coordinates'][1:])))
        p['collectors'].append(dict(id=cid,code=f'COLL_DEMO_0{number}',description='Coll_'+town,type='CV',visits_h1=2,visits_h2=2,hours_km_visit=2,length_m=sum(s['length_m'] for s in segments),length_source='ESTIMATED',length_complete=True,synthetic=True,archived=False))
        if town=='Lavis':p['points']+=points;p['segments']+=segments
    # Keep the synthetic basemap, clearly marked as synthetic; OSM remains a separate source.
    raw=json.dumps(p,ensure_ascii=False,indent=2)+'\n'
    (ROOT/'demo/trento-lavis-v0.13.json').write_text(raw,encoding='utf-8')
    (ROOT/'android/app/src/demo/assets/demo-package.json').write_text(raw,encoding='utf-8')
if __name__=='__main__':main()
