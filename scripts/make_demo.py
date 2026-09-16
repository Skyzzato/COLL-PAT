"""Generate synthetic shapefiles. Never samples or infers real PAT assets."""
import argparse
import json
import tempfile
import zipfile
from pathlib import Path
import shapefile
from pyproj import CRS

def generate(folder: Path, count=16):
    folder.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        root=Path(tmp)
        positions=[(11.1100+(i%100)*0.00052,46.0700+(i//100)*0.00045) for i in range(count)]
        with shapefile.Writer(str(root/"points"), shapeType=shapefile.POINT, encoding="utf-8") as w:
            w.field("KEY","C",size=40);w.field("NUMBER","C",size=20);w.field("MAP_ERR","N",size=10,decimal=2)
            for i,(lon,lat) in enumerate(positions):
                w.point(lon,lat);w.record(f"SYN-{i:06d}",f"{i%100:04d}",None if i%5==0 else 2.0)
        with shapefile.Writer(str(root/"segments"), shapeType=shapefile.POLYLINE, encoding="utf-8") as w:
            for field in ("KEY","COLLECTOR","START","END"):w.field(field,"C",size=40)
            for i in range(count-1):
                if i % 100 == 99: continue
                w.line([[positions[i],positions[i+1]]]);w.record(f"SEG-{i:06d}",f"DEMO-{i//100:03d}",f"SYN-{i:06d}",f"SYN-{i+1:06d}")
        for name in ("points","segments"):
            (root/f"{name}.prj").write_text(CRS.from_epsg(4326).to_wkt(),encoding="utf-8")
            (root/f"{name}.cpg").write_text("UTF-8")
        lon,lat=positions[0];last=positions[-1]
        base={"type":"FeatureCollection","features":[
            {"type":"Feature","properties":{"name":"AREA DIMOSTRATIVA"},"geometry":{"type":"Polygon","coordinates":[[[lon-.003,lat-.003],[max(x[0] for x in positions)+.003,lat-.003],[max(x[0] for x in positions)+.003,last[1]+.003],[lon-.003,last[1]+.003],[lon-.003,lat-.003]]]}},
            {"type":"Feature","properties":{"name":"Strada sintetica"},"geometry":{"type":"LineString","coordinates":[[lon-.003,lat-.0003],[max(x[0] for x in positions)+.003,lat-.0003]]}}
        ]}
        (root/"basemap.geojson").write_text(json.dumps(base),encoding="utf-8")
        with zipfile.ZipFile(folder/"synthetic.zip","w",zipfile.ZIP_DEFLATED) as z:
            for p in root.iterdir():z.write(p,p.name)
    config={"area_id":"DEMO","synthetic":True,"points":{"file":"points","key":"KEY","code":"NUMBER","uncertainty":"MAP_ERR"},
            "segments":{"file":"segments","key":"KEY","collector":"COLLECTOR","from":"START","to":"END"},
            "basemap":"basemap.geojson","attribution":"Dati e base interamente sintetici — Collettori PAT pilota. Nessun manufatto reale."}
    (folder/"mapping.json").write_text(json.dumps(config,indent=2),encoding="utf-8")
    return folder/"synthetic.zip",config

if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--output",default="demo");parser.add_argument("--count",type=int,default=16);args=parser.parse_args()
    generate(Path(args.output),args.count)
    print(f"Generati {args.count} punti SINTETICI in {args.output}")
