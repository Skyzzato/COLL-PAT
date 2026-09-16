"""Real HTTP smoke test, isolated synthetic database, optional headless browser.
No existing service or database is modified. Child server is stopped in finally.
"""
import argparse
import json
import os
import secrets
import socket
import subprocess
import sys
import time
from uuid import uuid4
from pathlib import Path
import httpx

def main():
    p=argparse.ArgumentParser();p.add_argument("--node");p.add_argument("--playwright");args=p.parse_args()
    root=Path(__file__).resolve().parents[1]
    folder=root/".tools"/f"smoke-{int(time.time())}";folder.mkdir(parents=True)
    env=dict(os.environ);env["DATABASE_URL"]="sqlite:///"+(folder/"smoke.db").as_posix()
    env["SHARED_DIR"]=str(root/"shared")
    subprocess.run([sys.executable,"-m","alembic","upgrade","head"],cwd=root/"backend",env=env,check=True)
    # Bootstrap only the isolated test account; its random password is never printed.
    password=secrets.token_urlsafe(24);env["SMOKE_PASSWORD"]=password
    seed="""
import os,json
from pathlib import Path
from app.db import Session
from app.models import Company,Area,User,Grant,Rule
from app.auth import passwords
from app.gis import import_zip
with Session() as db:
 c=Company(name='Impresa SINTETICA collaudo');db.add(c);db.flush()
 db.add(Area(id='DEMO',name='Area SINTETICA collaudo',synthetic=True));db.flush()
 u=User(username='collaudo.web',password_hash=passwords.hash(os.environ['SMOKE_PASSWORD']),role='admin',company_id=c.id);db.add(u);db.flush()
 db.add(Grant(user_id=u.id,area_id='DEMO'))
 r=json.loads((Path(os.environ['SHARED_DIR'])/'gps-rule.json').read_text());db.add(Rule(id=r['version'],parameters=r));db.commit()
 import_zip(db,'../demo/synthetic.zip',json.loads(Path('../demo/mapping.json').read_text()),Path(os.environ['SMOKE_IMPORTS']))
"""
    env["SMOKE_IMPORTS"]=str(folder/"imports")
    subprocess.run([sys.executable,"-c",seed],cwd=root/"backend",env=env,check=True)
    with socket.socket() as sock:sock.bind(("127.0.0.1",0));port=sock.getsockname()[1]
    base=f"http://127.0.0.1:{port}";env["SMOKE_BASE"]=base;env["SMOKE_OUTPUT"]=str(folder)
    log=(folder/"server.log").open("w")
    proc=subprocess.Popen([sys.executable,"-m","uvicorn","app.main:app","--host","127.0.0.1","--port",str(port)],cwd=root/"backend",env=env,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW if os.name=="nt" else 0)
    try:
        for _ in range(100):
            try:
                if httpx.get(base+"/health").status_code==200:break
            except httpx.HTTPError: pass
            time.sleep(.1)
        with httpx.Client(base_url=base) as client:
            assert client.get("/health").json()["version"]=="0.1"
            assert client.get("/").status_code==200
            auth=client.post("/api/login",json={"username":"collaudo.web","password":password,"device_id":"live-http-test"});auth.raise_for_status()
            client.headers["Authorization"]="Bearer "+auth.json()["access_token"]
            catalog=client.get("/api/catalog").json();assert len(catalog["datasets"])==1
            pack=client.get("/api/datasets/"+catalog["datasets"][0]["id"]).json();assert len(pack["points"])==16
            point=pack["points"][1];iid=str(uuid4());t="2026-09-16T08:00:00Z"
            visit={"id":iid,"manhole_id":point["id"],"dataset_id":pack["version"],"user_id":auth.json()["user_id"],"device_id":"live-http-test","selection_method":"LIST","started_at":t,"completed_at":t,"revision":1,"status":"COMPLETO",
                   "sheet":{"accessible":True,"opened":True,"cover":"REGOLARE","deposits":"REGOLARE","flow":"ANOMALO","walls":"REGOLARE","damage":"REGOLARE","closure":"REGOLARE","restored":"REGOLARE","anomaly_note":"Ristagno SINTETICO per collaudo"},
                   "events":[{"id":str(uuid4()),"inspection_id":iid,"manhole_id":point["id"],"user_id":auth.json()["user_id"],"device_id":"live-http-test","latitude":point["latitude"],"longitude":point["longitude"],"accuracy_m":3,"age_s":1,"requested_at":t,"acquired_at":t,"permission":"PRECISE","provider":"synthetic-http-smoke","mock":False,"app_version":"0.1","dataset_id":pack["version"],"rule_version":"gps-1","local_evaluation":{"state":"COMPATIBILE"}}]}
            sent=client.post("/api/sync",json={"operation_id":str(uuid4()),"inspection":visit});sent.raise_for_status()
            report=client.post("/api/reports?area=DEMO&year=2026&quarter=3&synthetic=true");report.raise_for_status()
            for fmt in ["xlsx","csv","json"]: assert client.get(f"/api/reports/{report.json()['id']}/{fmt}").status_code==200
        if args.node:
            env["PLAYWRIGHT_PACKAGE"]=args.playwright or "playwright"
            subprocess.run([args.node,str(root/"scripts/web-smoke.cjs")],cwd=root,env=env,check=True)
        print("HTTP reale: health, portale, login, catalogo, dataset e tre export OK.")
        print("Evidenze del collaudo:",folder)
    finally:
        proc.terminate()
        try:proc.wait(timeout=10)
        except subprocess.TimeoutExpired:proc.kill();proc.wait()
        log.close()

if __name__=="__main__":main()
