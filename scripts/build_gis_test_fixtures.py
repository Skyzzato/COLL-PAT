"""Small synthetic shapefiles for on-device parser tests, never operational data."""
from pathlib import Path
import io,zipfile
import shapefile
from pyproj import CRS,Transformer
ROOT=Path(__file__).resolve().parents[1]/'android/app/src/test/resources/gis'
def layer(kind,crs=4326):
    shp,shx,dbf=io.BytesIO(),io.BytesIO(),io.BytesIO()
    w=shapefile.Writer(shp=shp,shx=shx,dbf=dbf,shapeType=kind,encoding='utf-8')
    for key in ['id','code','descr','ramo','ordine','tipo','from_id','to_id']:w.field(key,'C',60)
    transform=Transformer.from_crs(4326,crs,always_xy=True)
    points=[transform.transform(11+i*.001,46+i*.001) for i in range(3)]
    if kind==shapefile.POINT:
        for i,p in enumerate(points):w.point(*p);w.record(str(i+1),f'000{i+1}','Punto sintetico','A',str(i),'MANHOLE','','')
    else:
        for i in range(2):w.line([[points[i],points[i+1]]]);w.record(str(i+1),f'T-{i+1}','Tratto sintetico','A',str(i),'',str(i+1),str(i+2))
    w.close();return dict(shp=shp.getvalue(),shx=shx.getvalue(),dbf=dbf.getvalue(),prj=CRS.from_epsg(crs).to_wkt(version='WKT1_ESRI').encode(),cpg=b'UTF-8')
def main():
    ROOT.mkdir(parents=True,exist_ok=True)
    for name,crs,lines in [('points',4326,False),('points-utm',32632,False),('points-lines',4326,True)]:
        with zipfile.ZipFile(ROOT/(name+'.zip'),'w',zipfile.ZIP_DEFLATED) as z:
            for stem,kind in [('points',shapefile.POINT)]+([('lines',shapefile.POLYLINE)] if lines else []):
                for ext,data in layer(kind,crs).items():
                    info=zipfile.ZipInfo(f'{stem}.{ext}',(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,data)
if __name__=='__main__':main()
