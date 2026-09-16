import json
import sys
from pathlib import Path
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from app.db import Base, session
from app.models import *
from app.main import app
from app.auth import passwords
from app.gis import import_zip
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/"scripts"))
from make_demo import generate

@pytest.fixture
def setup(tmp_path):
    engine=create_engine(f"sqlite:///{tmp_path/'test.db'}",connect_args={"check_same_thread":False})
    Base.metadata.create_all(engine);factory=sessionmaker(engine,expire_on_commit=False)
    source,config=generate(tmp_path/"demo",16)
    with factory() as db:
        db.add(Area(id="DEMO",name="Area sintetica",synthetic=True));db.add(Area(id="OTHER",name="Altra area",synthetic=False))
        co=Company(name="Impresa sintetica");db.add(co);db.flush()
        user=User(username="worker",password_hash=passwords.hash("test-only-password"),role="operaio",company_id=co.id)
        admin=User(username="admin",password_hash=passwords.hash("test-only-password"),role="admin",company_id=co.id)
        db.add_all([user,admin]);db.flush();db.add_all([Grant(user_id=user.id,area_id="DEMO"),Grant(user_id=admin.id,area_id="DEMO")])
        rule=json.loads((Path(__file__).resolve().parents[2]/"shared/gps-rule.json").read_text());db.add(Rule(id=rule["version"],parameters=rule));db.commit()
        imported=import_zip(db,source,config,tmp_path/"imports")
        data=db.get(Dataset,imported["id"]).payload
    def override():
        with factory() as db: yield db
    app.dependency_overrides[session]=override
    with TestClient(app) as client:
        login=client.post("/api/login",json={"username":"worker","password":"test-only-password","device_id":"device-test"}).json()
        a=client.post("/api/login",json={"username":"admin","password":"test-only-password","device_id":"admin-test"}).json()
        yield dict(client=client,db=factory,user=user,admin=admin,company=co,data=data,headers={"Authorization":"Bearer "+login["access_token"]},admin_headers={"Authorization":"Bearer "+a["access_token"]},login=login,config=config,source=source,tmp=tmp_path)
    app.dependency_overrides.clear();engine.dispose()

def operation(s,point=1):
    p=s["data"]["points"][point];i=uid();t="2026-06-30T20:00:00+00:00"
    sheet=dict(accessible=True,opened=True,no_open_reason="",unsafe=False,cover="REGOLARE",deposits="REGOLARE",flow="REGOLARE",walls="REGOLARE",damage="REGOLARE",cleaning=False,closure="REGOLARE",restored="REGOLARE",anomaly_note="",priority="MEDIA",technical_value="",technical_origin="NON_NOTO",notes="",exception_reason="GPS da verificare",map_position_wrong=False)
    e=dict(id=uid(),inspection_id=i,manhole_id=p["id"],user_id=s["user"].id,device_id="device-test",latitude=p["latitude"],longitude=p["longitude"],accuracy_m=3,age_s=1,requested_at=t,acquired_at=t,provider="test-explicit-synthetic",permission="PRECISE",mock=False,error=None,app_version="0.1",dataset_id=s["data"]["version"],rule_version="gps-1",local_evaluation={"state":"COMPATIBILE"})
    return dict(operation_id=uid(),inspection=dict(id=i,manhole_id=p["id"],dataset_id=s["data"]["version"],device_id="device-test",user_id=s["user"].id,selection_method="LIST",started_at=t,completed_at=t,status="COMPLETO",sheet_version="sheet-1",sheet=sheet,events=[e],deadline_id=None,revision=1,revision_reason=""))
