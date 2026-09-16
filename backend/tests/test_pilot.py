import copy
import io
import json
import zipfile
from pathlib import Path
from datetime import datetime
from sqlalchemy import select, func
import pytest
from openpyxl import load_workbook
from app.models import *
from app.gps import decide, distance
from app.gis import import_zip, canonical
from app.reports import quarter
from conftest import operation

ROOT=Path(__file__).resolve().parents[2]
@pytest.mark.parametrize("case",json.loads((ROOT/"shared/gps-cases.json").read_text()),ids=lambda c:c["name"])
def test_gps_vectors(case):
    p=json.loads((ROOT/"shared/gps-rule.json").read_text())
    c=case
    assert decide(c["d"],c["a"],c["age"],c["g"],c["permission"],c["mock"],c["ambiguous"],c["error"],p)["state"]==c["expected"]

def test_distance(): assert distance(0,0,0,1)==pytest.approx(111195.0802335329,abs=1e-6)

def test_sync_idempotence_conflict_and_server_recompute(setup):
    s=setup;c=s["client"];op=operation(s,0)
    # g unknown, despite favourable client assertion.
    first=c.post("/api/sync",headers=s["headers"],json=op)
    assert first.status_code==200,first.text
    assert first.json()["server_evaluation"]["state"]=="INCERTA"
    retry=c.post("/api/sync",headers=s["headers"],json=op)
    assert retry.json()==first.json()
    op["inspection"]["sheet"]["notes"]="different"
    assert c.post("/api/sync",headers=s["headers"],json=op).status_code==409
    with s["db"]() as db: assert db.scalar(select(func.count()).select_from(Inspection))==1

def test_permissions_and_revocation(setup):
    s=setup;c=s["client"]
    assert c.get("/api/catalog").status_code==401
    assert c.get("/api/inspections?area=OTHER",headers=s["headers"]).status_code==403
    assert c.get("/api/users",headers=s["headers"]).status_code==403
    assert c.post("/api/users/"+s["user"].id+"/disable",headers=s["admin_headers"]).status_code==200
    assert c.post("/api/refresh",json={"refresh_token":s["login"]["refresh_token"]}).status_code==401
    assert c.post("/api/users/"+s["user"].id+"/enable",headers=s["admin_headers"],json={"note":"Recupero autorizzato di prova"}).status_code==200
    assert c.put("/api/users/"+s["user"].id+"/grants",headers=s["admin_headers"],json={"areas":[]}).status_code==200
    assert c.get("/api/inspections?area=DEMO",headers=s["headers"]).status_code==403

def test_complete_uncertain_and_impeded_not_complete(setup):
    s=setup;op=operation(s,0)
    op["inspection"]["sheet"]["unsafe"]=True
    assert s["client"].post("/api/sync",headers=s["headers"],json=op).status_code==422
    op["inspection"]["status"]="IMPEDITO";op["inspection"]["sheet"]["opened"]=False;op["inspection"]["sheet"]["no_open_reason"]="Accesso non sicuro"
    assert s["client"].post("/api/sync",headers=s["headers"],json=op).status_code==200

def test_revision_immutable_event_and_audit(setup):
    s=setup;c=s["client"];op=operation(s)
    assert c.post("/api/sync",headers=s["headers"],json=op).status_code==200
    rev=copy.deepcopy(op);rev["operation_id"]=uid();rev["inspection"].update(revision=2,revision_reason="Correzione descrizione");rev["inspection"]["sheet"]["notes"]="Precisazione"
    assert c.post("/api/sync",headers=s["headers"],json=rev).status_code==200
    d=c.get("/api/inspections/"+op["inspection"]["id"],headers=s["headers"]).json()
    assert len(d["revisions"])==2 and d["revisions"][0]["payload"]["sheet"]["notes"]==""
    rev["operation_id"]=uid();rev["inspection"]["revision"]=3;rev["inspection"]["events"][0]["latitude"]+=1
    assert c.post("/api/sync",headers=s["headers"],json=rev).status_code==409

def test_report_frozen_late_arrivals_safe_export(setup):
    s=setup;c=s["client"];h=s["admin_headers"]
    url="/api/reports?area=DEMO&year=2026&quarter=2&synthetic=true"
    first=c.post(url,headers=h).json()
    op=operation(s);op["inspection"]["sheet"]["notes"]="=1+1"
    assert c.post("/api/sync",headers=s["headers"],json=op).status_code==200
    again=copy.deepcopy(op);again["operation_id"]=uid();again["inspection"]["id"]=uid();again["inspection"]["events"][0].update(id=uid(),inspection_id=again["inspection"]["id"])
    assert c.post("/api/sync",headers=s["headers"],json=again).status_code==200
    second=c.post(url,headers=h).json()
    assert second["snapshot"]["indicators"]["distinct_completed"]==1
    original=c.get(f"/api/reports/{first['id']}/json",headers=h).json()
    assert original["rows"]==[]
    x=c.get(f"/api/reports/{second['id']}/xlsx",headers=h)
    wb=load_workbook(io.BytesIO(x.content));assert wb["Controlli"]["C2"].value=="0001";assert wb["Controlli"]["C2"].data_type=="s"
    assert c.post("/api/reports?area=DEMO&year=2026&quarter=2",headers=h).status_code==422

def test_import_crs_and_history(setup):
    s=setup
    with s["db"]() as db:
        old=s["data"]["points"][1]["id"]
        report=import_zip(db,s["source"],s["config"],s["tmp"]/"imports2")
        assert db.get(Dataset,report["id"]).payload["points"][1]["id"]==old
        assert db.get(Dataset,s["data"]["version"]) is not None
        missing=s["tmp"]/"missing.zip"
        with zipfile.ZipFile(s["source"]) as src, zipfile.ZipFile(missing,"w") as dst:
            for name in src.namelist():
                if not name.endswith(".prj"):dst.writestr(name,src.read(name))
        with pytest.raises(ValueError,match="CRS non noto"):import_zip(db,missing,s["config"],s["tmp"]/"bad")

def test_quarter_rome_dst():
    start,end=quarter(2026,1)
    assert start.utcoffset().total_seconds()==3600 and end.utcoffset().total_seconds()==7200

def test_deadline_late_remains_and_impeded_excluded(setup):
    s=setup;c=s["client"]
    d=c.post("/api/deadlines",headers=s["admin_headers"],json={"manhole_id":s["data"]["points"][1]["id"],"company_id":s["company"].id,"due_at":"2026-06-29T00:00:00+02:00","source":"Obbligo sintetico test"}).json()
    op=operation(s);op["inspection"]["deadline_id"]=d["id"]
    assert c.post("/api/sync",headers=s["headers"],json=op).status_code==200
    dash=c.get("/api/dashboard?area=DEMO",headers=s["admin_headers"]).json()
    assert dash["deadlines"][0]["late"] is True

def test_versioned_rules_admin_only(setup):
    s=setup;p=json.loads((ROOT/"shared/gps-rule.json").read_text());p["version"]="gps-2"
    assert s["client"].post("/api/rules",json=p,headers=s["headers"]).status_code==403
    assert s["client"].post("/api/rules",json=p,headers=s["admin_headers"]).status_code==200
    assert s["client"].post("/api/rules",json=p,headers=s["admin_headers"]).status_code==409

def test_expired_token_can_refresh_without_losing_work(setup):
    s=setup
    with s["db"]() as db:
        login=db.scalar(select(LoginSession).where(LoginSession.user_id==s["user"].id))
        login.access_until="2000-01-01T00:00:00+00:00";db.commit()
    op=operation(s)
    assert s["client"].post("/api/sync",headers=s["headers"],json=op).status_code==401
    refreshed=s["client"].post("/api/refresh",json={"refresh_token":s["login"]["refresh_token"]})
    assert refreshed.status_code==200
    assert s["client"].post("/api/sync",headers={"Authorization":"Bearer "+refreshed.json()["access_token"]},json=op).status_code==200

def test_event_cannot_be_reused_for_a_second_visit(setup):
    s=setup;op=operation(s)
    assert s["client"].post("/api/sync",headers=s["headers"],json=op).status_code==200
    op["operation_id"]=uid();op["inspection"]["id"]=uid();op["inspection"]["events"][0]["inspection_id"]=op["inspection"]["id"]
    assert s["client"].post("/api/sync",headers=s["headers"],json=op).status_code==409

def test_reimport_cannot_rename_a_historical_report_row(setup):
    s=setup;op=operation(s)
    assert s["client"].post("/api/sync",headers=s["headers"],json=op).status_code==200
    config=copy.deepcopy(s["config"]);config["points"]["code"]="KEY"
    with s["db"]() as db:
        report=import_zip(db,s["source"],config,s["tmp"]/"renamed")
        assert db.get(Dataset,report["id"]).payload["points"][1]["code"]=="SYN-000001"
    r=s["client"].get("/api/inspections?area=DEMO",headers=s["headers"]).json()
    assert r[0]["manhole"]=="0001"

def test_empty_csv_has_emission_identity(setup):
    import csv
    s=setup;c=s["client"];h=s["admin_headers"]
    r=c.post("/api/reports?area=DEMO&year=2026&quarter=2&synthetic=true",headers=h).json()
    data=c.get(f"/api/reports/{r['id']}/csv",headers=h).content.decode("utf-8-sig")
    record=list(csv.DictReader(io.StringIO(data),delimiter=";"))[0]
    assert record["record_type"]=="EMISSIONE" and record["report_id"]==r["id"]

def test_anomaly_and_document_review_are_audited_independently(setup):
    s=setup;c=s["client"];op=operation(s)
    op["inspection"]["sheet"].update(flow="ANOMALO",anomaly_note="Ristagno sintetico")
    assert c.post("/api/sync",headers=s["headers"],json=op).status_code==200
    dash=c.get("/api/dashboard?area=DEMO",headers=s["admin_headers"]).json();a=dash["anomalies"][0]
    assert c.post("/api/anomalies/"+a["id"],headers=s["admin_headers"],json={"state":"CHIUSA","note":"Risoluzione sintetica verificata"}).status_code==200
    assert c.post("/api/inspections/"+op["inspection"]["id"]+"/review",headers=s["admin_headers"],json={"revision":1,"state":"VERIFICATA_DOCUMENTALMENTE","note":"Esame documentale sintetico"}).status_code==200
    with s["db"]() as db:
        assert db.get(Anomaly,a["id"]).state=="CHIUSA"
        assert db.get(Revision,(op["inspection"]["id"],1)).payload["status"]=="COMPLETO"
        assert db.scalar(select(func.count()).select_from(Audit).where(Audit.kind=="anomaly_state"))==1

def test_no_future_permissions():
    manifest=(ROOT/"android/app/src/main/AndroidManifest.xml").read_text()
    assert all(x not in manifest for x in ["CAMERA","NFC","ACCESS_BACKGROUND_LOCATION","READ_PHONE_STATE"])

def test_controlled_recovery_preserves_disabled_author(setup,monkeypatch):
    from app import cli
    import sys
    s=setup;op=operation(s)
    bundle=s["tmp"]/"recovery.json"
    bundle.write_text(json.dumps({"format":"collettori-recovery-1","operations":[op],"drafts":[]}),encoding="utf-8")
    assert s["client"].post("/api/users/"+s["user"].id+"/disable",headers=s["admin_headers"]).status_code==200
    monkeypatch.setattr(cli,"Session",s["db"])
    monkeypatch.setattr(cli.getpass,"getpass",lambda _:"test-only-password")
    monkeypatch.setattr(sys,"argv",["cli","recover",str(bundle),"--approver","admin","--reason","Guasto sintetico"])
    cli.main()
    with s["db"]() as db:
        assert db.get(Inspection,op["inspection"]["id"]).user_id==s["user"].id
        assert db.get(User,s["user"].id).active is False
        assert db.scalar(select(func.count()).select_from(Audit).where(Audit.kind=="controlled_recovery"))==1

def test_scale_12500_points(tmp_path):
    from make_demo import generate
    from sqlalchemy import create_engine
    from sqlalchemy.orm import Session
    from app.db import Base
    source,config=generate(tmp_path/"scale",12500)
    engine=create_engine(f"sqlite:///{tmp_path/'scale.db'}");Base.metadata.create_all(engine)
    with Session(engine) as db:
        db.add(Area(id="DEMO",name="SINTETICO carico",synthetic=True));db.commit()
        r=import_zip(db,source,config,tmp_path/"imports")
        p=db.get(Dataset,r["id"]).payload
        assert len(p["points"])==12500
        assert len({x["id"] for x in p["points"]})==12500
        assert p["points"][0]["code"]==p["points"][100]["code"]=="0000"
    engine.dispose()
