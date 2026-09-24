"""Explicit v0.22 test dataset, never an automatic startup seed. Standard library only."""
import json
import math
import uuid
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
NAMESPACE=uuid.UUID('584f220c-63d8-4ef8-8656-f6c86c679faa')
DATASET='DEMO-BAR-5KM-v022'
LAT=46+6/60+5.90/3600
LON=11+11/60+9.06/3600
def ident(kind,index=0):return str(uuid.uuid5(NAMESPACE,f'{DATASET}:{kind}:{index}'))
def distance(a,b):
    lat1,lon1=map(math.radians,a);lat2,lon2=map(math.radians,b)
    return 6371008.8*2*math.asin(min(1,math.sqrt(math.sin((lat2-lat1)/2)**2+math.cos(lat1)*math.cos(lat2)*math.sin((lon2-lon1)/2)**2)))
def generate():
    def route(scale):
        return [(LAT+math.degrees(scale*(i-62)*0.4/6371008.8),LON+math.degrees(scale*((i-62)+5*math.sin((i-62)/12))/(6371008.8*math.cos(math.radians(LAT))))) for i in range(126)]
    lo,hi=0,100
    for _ in range(70):
        scale=(lo+hi)/2;coords=route(scale)
        if sum(distance(a,b) for a,b in zip(coords,coords[1:]))<5000:lo=scale
        else:hi=scale
    coords=route((lo+hi)/2);cid=ident('collector');lengths=[distance(a,b) for a,b in zip(coords,coords[1:])]
    shared=dict(synthetic=True,synthetic_dataset=DATASET,source=DATASET)
    collector=dict(id=cid,code='DEMO-BAR-5KM',description='Collettore simulato Barbaniga – Civezzano',type='CV',visits_h1=2,visits_h2=2,hours_km_visit=2,
        length_m=sum(lengths),length_source='ESTIMATED',length_complete=True,display_color=None,display_width=None,symbol=None,archived=False,**shared)
    points=[];segments=[];chain=0
    for i,(lat,lon) in enumerate(coords):
        p=dict(id=ident('point',i),code=f'DEMO-BAR-{i+1:03}',description='Pozzetto sintetico: rete inventata, non operativa',latitude=lat,longitude=lon,
            collectors=[cid],asset_type='MANHOLE',uncertainty_m=None,under_asphalt=False,sequence=i,branch='SIMULATO',origin_id=ident('point'),chainage_m=chain,
            chainage_source='CALCULATED_SCHEMATIC',next_ids=[ident('point',i+1)] if i<125 else [],topology_end=i==125,**shared)
        if i:p.update(previous_id=ident('point',i-1),previous_distance_m=lengths[i-1])
        points.append(p)
        if i<125:
            geometry=dict(type='LineString',coordinates=[[lon,lat],[coords[i+1][1],coords[i+1][0]]])
            segments.append(dict(id=ident('segment',i),collectors=[cid],from_id=ident('point',i),to_id=ident('point',i+1),geometry=geometry,length_m=lengths[i],schematic=True,length_source='ESTIMATED',flow_direction='UNSPECIFIED',**shared))
            chain+=lengths[i]
    return dict(dataset=DATASET,description='Percorso inventato per collaudo, non rappresenta la rete fognaria reale.',
        locality=dict(name='Barbaniga, Civezzano',latitude=LAT,longitude=LON,
            toponym_source='https://www.cultura.trentino.it/Patrimonio-on-line/Dizionario-toponomastico-trentino/AreaVisitatore/DetailsPage.aspx?param=2733548',
            coordinate_source='https://www.wikidata.org/wiki/Q18478677',geonames_id='8958102'),collectors=[collector],points=points,segments=segments)

if __name__=='__main__':
    result=generate();path=ROOT/'shared/demo-barbaniga-v022.json'
    path.write_text(json.dumps(result,ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8')
    print(f'{path.name}: 126 pozzetti, 125 tronchi, {result["collectors"][0]["length_m"]:.6f} m')
