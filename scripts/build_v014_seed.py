"""Add synthetic Via Gilli geometry, preserving every v0.13 asset ID."""
import json
from pathlib import Path
from build_v013_seed import uid, distance
ROOT=Path(__file__).resolve().parents[1]
def main():
    data=json.loads((ROOT/'demo/trento-lavis-v0.13.json').read_text(encoding='utf-8'))
    cid=uid('collector:Trento-Gilli')
    coordinates=[(11.11850,46.09105),(11.11885,46.09080),(11.11923,46.09060),(11.1195695,46.0904585),(11.12003,46.09021),(11.12048,46.08996)]
    points=[];segments=[];chain=0
    for i,(lon,lat) in enumerate(coordinates):
        p=dict(id=uid(f'Gilli:point:{i}'),code=f'GL-{i+1:03}',latitude=lat,longitude=lon,uncertainty_m=2,collectors=[cid],collector_id=cid,description='Pozzetto sintetico Via Gilli',asset_type='MANHOLE',synthetic=True,under_asphalt=i==2,sequence=i,branch='DEMO-GILLI',origin_id=uid('Gilli:point:0'),chainage_source='CALCULATED_SCHEMATIC')
        if i:
            length=distance(coordinates[i-1],coordinates[i]);chain+=length;p.update(previous_id=points[-1]['id'],previous_distance_m=length)
            segments.append(dict(id=uid(f'Gilli:segment:{i-1}'),from_id=points[-1]['id'],to_id=p['id'],collectors=[cid],collector_id=cid,synthetic=True,schematic=True,length_m=length,geometry=dict(type='LineString',coordinates=[coordinates[i-1],coordinates[i]])))
        p['chainage_m']=chain;points.append(p)
    data['collectors'].append(dict(id=cid,code='COLL_DEMO_03',description='Demo Via Gilli · Trento',type='CZI',visits_h1=2,visits_h2=2,hours_km_visit=2,length_m=chain,length_source='ESTIMATED',length_complete=True,synthetic=True,archived=False,display_color='#176D73'))
    for c in data['collectors']:c.setdefault('display_color','#176D73')
    data['points']+=points;data['segments']+=segments;data['name']='COLL-PAT · demo Trento, Lavis e Via Gilli'
    raw=json.dumps(data,ensure_ascii=False,indent=2)+'\n'
    (ROOT/'demo/trento-lavis-gilli-v0.14.json').write_text(raw,encoding='utf-8')
    (ROOT/'android/app/src/demo/assets/demo-package.json').write_text(raw,encoding='utf-8')
if __name__=='__main__':main()
