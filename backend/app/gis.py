"""One pipeline: zipped shapefiles -> validated immutable JSON snapshot -> Room/MapLibre.
Stable source IDs are mandatory. Renumbering must retain source IDs.
"""
import hashlib
import io
import json
import math
import shutil
import zipfile
from pathlib import Path
from uuid import uuid5, NAMESPACE_URL
import shapefile
from pyproj import CRS, Transformer
from sqlalchemy import select, text
from .models import Area, Collector, Manhole, Segment, Connection, Dataset, uid

def canonical(value): return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode("utf-8")
def stable(area, kind, key): return str(uuid5(NAMESPACE_URL, f"collettori-pat/{area}/{kind}/{key}"))

def read_layer(z, spec, errors):
    name = spec["file"]
    if name.endswith(".shp"): name = name[:-4]
    required = [name + ext for ext in (".shp", ".shx", ".dbf")]
    if any(p not in z.namelist() for p in required): raise ValueError(f"File accessori mancanti: {name}")
    crs_text = z.read(name+".prj").decode("utf-8-sig") if name+".prj" in z.namelist() else spec.get("crs")
    if not crs_text: raise ValueError(f"CRS non noto: pubblicazione bloccata ({name})")
    crs = CRS.from_user_input(crs_text)
    encoding = spec.get("encoding") or (z.read(name+".cpg").decode("ascii").strip() if name+".cpg" in z.namelist() else None)
    if not encoding: raise ValueError(f"Codifica non nota: configurare encoding ({name})")
    if encoding == "65001": encoding = "utf-8"
    transformer = Transformer.from_crs(crs, CRS.from_epsg(4326), always_xy=True)
    reader = shapefile.Reader(shp=io.BytesIO(z.read(required[0])), shx=io.BytesIO(z.read(required[1])), dbf=io.BytesIO(z.read(required[2])), encoding=encoding)
    fields = {x[0]: x[1] for x in reader.fields[1:]}
    for key in ["key", "code", "collector", "from", "to"]:
        field = spec.get(key)
        if field and (field not in fields or fields[field] != "C"):
            raise ValueError(f"{name}: il campo {field} deve essere testuale, per preservare i codici")
    records = []
    seen = set()
    for row_no, sr in enumerate(reader.iterShapeRecords(), 1):
        attrs = sr.record.as_dict()
        key = str(attrs.get(spec["key"], "")).strip()
        if not key or key in seen:
            errors.append(f"{name} riga {row_no}: chiave assente o duplicata {key}")
            continue
        seen.add(key)
        try:
            geometry = sr.shape.__geo_interface__
            def transform(coords):
                if isinstance(coords[0], (float, int)):
                    lon, lat = transformer.transform(coords[0], coords[1], errcheck=True)
                    if not math.isfinite(lon+lat) or not (-180 <= lon <= 180 and -90 <= lat <= 90): raise ValueError("coordinate non valide")
                    return [lon, lat]
                return [transform(c) for c in coords]
            geometry = {"type": geometry["type"], "coordinates": transform(geometry["coordinates"])}
            records.append((key, attrs, geometry))
        except Exception as exc: errors.append(f"{name} riga {row_no}: geometria problematica ({exc})")
    return records, {"crs": crs.to_string(), "encoding": encoding, "count": len(records)}

def import_zip(db, source, config, storage=Path("runtime/imports")):
    config = dict(config)
    storage = Path(storage)
    job = uid()
    folder = storage / job
    folder.mkdir(parents=True)
    shutil.copyfile(source, folder / "source.zip")
    (folder / "mapping.json").write_bytes(canonical(config))
    report = {"id": job, "errors": [], "warnings": [], "published": False}
    try:
        area_id = config["area_id"]
        area = db.get(Area, area_id)
        if not area: raise ValueError("Creare prima l'area e definirne la natura sintetica/operativa")
        if area.synthetic != config["synthetic"]: raise ValueError("Separazione dimostrativo/operativo non rispettata")
        with zipfile.ZipFile(source) as z:
            if sum(f.file_size for f in z.infolist()) > 200_000_000: raise ValueError("Archivio superiore al limite pilota di 200 MB")
            point_rows, report["points"] = read_layer(z, config["points"], report["errors"])
            line_rows, report["segments"] = read_layer(z, config["segments"], report["errors"])
            base = json.loads(z.read(config["basemap"])) if config.get("basemap") else None
        if base is not None:
            if base.get("type") != "FeatureCollection" or not config.get("attribution"): raise ValueError("Base: GeoJSON e attribuzione obbligatori")
            # Reuse pyproj identity validation to reject non-geographic / invalid coordinates.
            def check_coords(c):
                if len(c) >= 2 and isinstance(c[0], (float, int)):
                    if not all(math.isfinite(v) for v in c[:2]) or not (-180 <= c[0] <= 180 and -90 <= c[1] <= 90): raise ValueError("Base fuori WGS84")
                else:
                    for child in c: check_coords(child)
            for f in base["features"]:
                if f["geometry"]["type"] not in ["Point", "LineString", "MultiLineString", "Polygon", "MultiPolygon"]: raise ValueError("Tipo geometria base non supportato")
                check_coords(f["geometry"]["coordinates"])
        ps, ls = config["points"], config["segments"]
        points, segments, collectors = {}, [], {}
        codes = set()
        coordinates = set()
        for key, attrs, geom in point_rows:
            code = str(attrs.get(ps["code"], ""))
            if not code.strip() or geom["type"] != "Point":
                report["errors"].append(f"Pozzetto {key}: codice o geometria non validi")
                continue
            lon, lat = geom["coordinates"]
            if (lon, lat) in coordinates: report["warnings"].append(f"Pozzetto {key}: coordinate duplicate, verificare")
            coordinates.add((lon, lat))
            g = attrs.get(ps.get("uncertainty"))
            g = float(g) if g is not None and g != "" else None
            if g is not None and (not math.isfinite(g) or g < 0): raise ValueError(f"Incertezza non valida: {key}")
            points[key] = {"id": stable(area_id, "point", key), "source_key": key, "code": code, "latitude": lat, "longitude": lon, "uncertainty_m": g, "collectors": [], "area_id": area_id}
        for key, attrs, geom in line_rows:
            code = str(attrs.get(ls["collector"], ""))
            start, end = str(attrs.get(ls["from"], "")).strip(), str(attrs.get(ls["to"], "")).strip()
            if not code.strip() or start not in points or end not in points or geom["type"] not in ["LineString", "MultiLineString"]:
                report["errors"].append(f"Tratto {key}: codice, estremi o geometria non validi")
                continue
            parts=[geom["coordinates"]] if geom["type"]=="LineString" else geom["coordinates"]
            if any(len(part)<2 or len({tuple(p) for p in part})<2 for part in parts):
                report["errors"].append(f"Tratto {key}: linea degenere")
                continue
            collectors[code] = {"id": stable(area_id, "collector", code), "code": code}
            segments.append({"id": stable(area_id, "segment", key), "source_key": key, "collector": code, "geometry": geom, "from_id": points[start]["id"], "to_id": points[end]["id"]})
            for endpoint in (start, end):
                if code not in points[endpoint]["collectors"]: points[endpoint]["collectors"].append(code)
        for point in points.values():
            if not point["collectors"]: report["errors"].append(f"Pozzetto {point['source_key']}: nessun tratto associato")
            for collector in point["collectors"]:
                pair = (collector, point["code"])
                if pair in codes: report["errors"].append(f"Codice duplicato nel collettore {pair}")
                codes.add(pair)
        if not points or not segments: report["errors"].append("Dataset vuoto")
        if report["errors"]: raise ValueError("Importazione bloccata: correggere il rapporto")
        old = db.scalars(select(Manhole).where(Manhole.area_id == area_id)).all()
        removed = [x.source_key for x in old if x.source_key not in points]
        if removed: report["warnings"].append(f"Manufatti assenti dalla nuova versione, storico conservato: {len(removed)}")
        report["published"] = True
        payload = {"version": job, "area_id": area_id, "name": area.name, "synthetic": area.synthetic,
                   "points": list(points.values()), "segments": segments, "basemap": base,
                   "attribution": config.get("attribution", "Base cartografica assente"),
                   "coverage": [min(x["longitude"] for x in points.values()), min(x["latitude"] for x in points.values()), max(x["longitude"] for x in points.values()), max(x["latitude"] for x in points.values())]}
        label_text = "".join(p["code"] for p in points.values()) + "".join(collectors)
        if base: label_text += "".join(str(f.get("properties",{}).get("name","")) for f in base["features"])
        payload["glyph_ranges"] = [f"{n}-{n+255}" for n in sorted({ord(c)//256*256 for c in label_text})]
        for c in collectors.values():
            if not db.get(Collector, c["id"]): db.add(Collector(id=c["id"], area_id=area_id, code=c["code"]))
        db.flush()
        for point in points.values():
            if not db.get(Manhole, point["id"]): db.add(Manhole(id=point["id"], area_id=area_id, source_key=point["source_key"]))
        for seg in segments:
            if not db.get(Segment, seg["id"]): db.add(Segment(id=seg["id"], area_id=area_id, source_key=seg["source_key"], collector_id=collectors[seg["collector"]]["id"]))
        db.flush()
        db.add(Dataset(id=job, area_id=area_id, payload=payload, source_path=str(folder), report=report, sha256=hashlib.sha256(canonical(payload)).hexdigest()))
        db.flush()
        for seg in segments:
            for endpoint, point_id in [("from", seg["from_id"]), ("to", seg["to_id"])]: db.add(Connection(dataset_id=job, segment_id=seg["id"], manhole_id=point_id, endpoint=endpoint))
        if db.bind.dialect.name == "postgresql":
            sql = text("INSERT INTO reference_geometries(dataset_id,entity_id,geom) VALUES (:dataset,:entity,ST_SetSRID(ST_GeomFromGeoJSON(:geom),4326))")
            geometries = [(p["id"], {"type": "Point", "coordinates": [p["longitude"], p["latitude"]]}) for p in points.values()] + [(s["id"], s["geometry"]) for s in segments]
            db.execute(sql, [{"dataset": job, "entity": i, "geom": json.dumps(g)} for i, g in geometries])
        (folder / "report.json").write_bytes(canonical(report))
        db.commit()
        return report
    except Exception as exc:
        db.rollback()
        report["published"] = False
        report["errors"].append(str(exc))
        raise ValueError(f"{exc}. Rapporto: {folder / 'report.json'}") from exc
    finally:
        if not report["published"] or not (folder / "report.json").exists():
            (folder / "report.json").write_bytes(canonical(report))
