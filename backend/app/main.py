import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid5, NAMESPACE_URL
from fastapi import FastAPI, Depends, HTTPException, Query
from fastapi.responses import Response, FileResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session
from .db import session
from .models import *
from .schemas import Login, Refresh, Operation, ReviewInput, AnomalyInput, UserInput, DeadlineInput, RuleInput, ActionNote, GrantsInput
from .auth import principal, passwords, issue, digest, allowed, area_check, reviewer, administrator
from .gps import evaluate
from .gis import canonical
from .reports import rows, snapshot, export_csv, export_xlsx, review_state

app = FastAPI(title="Collettori", version="0.1")
static = Path(__file__).parent / "static"
app.mount("/static", StaticFiles(directory=static), name="static")

@app.middleware("http")
async def headers(request, call_next):
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Cache-Control"] = "no-store"
    response.headers["Content-Security-Policy"] = "default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'none'"
    return response

@app.get("/")
def admin_page(): return FileResponse(static / "index.html")

@app.get("/health")
def health(): return {"version": "0.1"}

@app.post("/api/login")
def login(body: Login, db: Session = Depends(session)):
    user = db.scalar(select(User).where(User.username == body.username))
    if not user or not user.active or not passwords.verify(body.password, user.password_hash): raise HTTPException(401, "Credenziali non valide")
    return issue(db, user, body.device_id)

@app.post("/api/refresh")
def refresh(body: Refresh, db: Session = Depends(session)):
    row = db.scalar(select(LoginSession).where(LoginSession.refresh_hash == digest(body.refresh_token)))
    if not row or row.refresh_until < now(): raise HTTPException(401, "Rinnovo non disponibile: effettuare accesso")
    user = db.get(User, row.user_id)
    if not user.active: raise HTTPException(401, "Utente disabilitato")
    return issue(db, user, row.device_id, row)

@app.post("/api/logout")
def logout(auth=Depends(principal), db: Session = Depends(session)):
    db.delete(db.get(LoginSession, auth[1].id)); db.commit()
    return {"revoked": True}

@app.get("/api/catalog")
def catalog(auth=Depends(principal), db: Session = Depends(session)):
    user = auth[0]
    datasets = []
    for area in allowed(db, user):
        latest = db.scalar(select(Dataset).where(Dataset.area_id == area).order_by(Dataset.created_at.desc()))
        if latest:
            p = latest.payload
            datasets.append({"id": latest.id, "area_id": area, "name": p["name"], "sha256": latest.sha256,
                             "bytes": len(canonical(p)), "coverage": p["coverage"], "synthetic": p["synthetic"], "basemap": p["basemap"] is not None, "created_at": latest.created_at})
    rule = db.scalar(select(Rule).order_by(Rule.created_at.desc()))
    deadlines = [dict(id=d.id, manhole_id=d.manhole_id, due_at=d.due_at, source=d.source, company_id=d.company_id) for d in db.scalars(select(Deadline).where(Deadline.area_id.in_(allowed(db, user)), Deadline.company_id == user.company_id))]
    return {"datasets": datasets, "rule": rule.parameters if rule else None, "deadlines": deadlines}

@app.get("/api/datasets/{dataset_id}")
def dataset(dataset_id: str, auth=Depends(principal), db: Session = Depends(session)):
    row = db.get(Dataset, dataset_id)
    if not row: raise HTTPException(404)
    area_check(db, auth[0], row.area_id)
    return Response(canonical(row.payload), media_type="application/json", headers={"X-Content-SHA256": row.sha256})

def receive(body, user, login, db):
    operation = body.model_dump(mode="json")
    sha = hashlib.sha256(canonical(operation)).hexdigest()
    oid = str(body.operation_id)
    existing_op = db.get(SyncOperation, oid)
    if existing_op:
        if existing_op.user_id != user.id or existing_op.digest != sha: raise HTTPException(409, "Identificativo operazione già usato con contenuto diverso")
        return existing_op.receipt
    p = body.inspection.model_dump(mode="json")
    if p["user_id"] != user.id or p["device_id"] != login.device_id: raise HTTPException(403, "Autore o dispositivo non corrispondente")
    dataset = db.get(Dataset, p["dataset_id"])
    if not dataset: raise HTTPException(422, "Versione cartografica non disponibile")
    area_check(db, user, dataset.area_id)
    point = next((x for x in dataset.payload["points"] if x["id"] == p["manhole_id"]), None)
    if point is None: raise HTTPException(422, "Pozzetto assente dalla versione dichiarata")
    item = db.scalar(select(Inspection).where(Inspection.id == p["id"]).with_for_update())
    if item:
        if item.user_id != user.id or item.dataset_id != p["dataset_id"] or item.manhole_id != p["manhole_id"] or item.device_id != p["device_id"]: raise HTTPException(409, "Identità della visita immutabile")
        if p["revision"] != item.current_revision + 1: raise HTTPException(409, "Revisione concorrente: verificare lo storico")
        previous = db.get(Revision, (item.id, item.current_revision)).payload
        if any(previous[k] != p[k] for k in ["events", "started_at", "completed_at", "selection_method"]): raise HTTPException(409, "Evidenze e completamento originari immutabili dopo il completamento")
    elif p["revision"] != 1: raise HTTPException(409, "Manca la revisione iniziale")
    if p.get("deadline_id"):
        d = db.get(Deadline, p["deadline_id"])
        if not d or d.manhole_id != p["manhole_id"] or d.company_id != user.company_id: raise HTTPException(422, "Scadenza non assegnata al manufatto e impresa")
    evaluations = []
    for e in p["events"]:
        rule = db.get(Rule, e["rule_version"])
        if not rule: raise HTTPException(422, "Regola sconosciuta: integrazione necessaria")
        evaluated = evaluate(e, point, dataset.payload["points"], rule.parameters)
        evaluations.append(evaluated)
        old = db.get(LocationEvent, e["id"])
        if old and (old.inspection_id != p["id"] or old.payload != e): raise HTTPException(409, "Evento già acquisito con contenuto diverso")
    if evaluations[-1]["state"] != "COMPATIBILE" and not p["sheet"]["exception_reason"].strip(): raise HTTPException(422, "Motivare l'eccezione GPS")
    if not item:
        item = Inspection(id=p["id"], user_id=user.id, company_id=user.company_id, manhole_id=p["manhole_id"], area_id=dataset.area_id,
                          dataset_id=dataset.id, device_id=p["device_id"], synthetic=dataset.payload["synthetic"], current_revision=1)
        db.add(item); db.flush()
    else: item.current_revision = p["revision"]
    db.add(Revision(inspection_id=item.id, number=p["revision"], author_id=user.id, reason=p["revision_reason"], payload=p, server_evaluation=evaluations[-1]))
    for e, ev in zip(p["events"], evaluations):
        if not db.get(LocationEvent, e["id"]): db.add(LocationEvent(id=e["id"], inspection_id=item.id, payload=e, server_evaluation=ev))
    if p["sheet"]["anomaly_note"].strip():
        anomaly_id = str(uuid5(NAMESPACE_URL, "anomaly/"+item.id))
        if not db.get(Anomaly, anomaly_id):
            db.add(Anomaly(id=anomaly_id, inspection_id=item.id, area_id=item.area_id, priority=p["sheet"]["priority"], description=p["sheet"]["anomaly_note"]))
        db.add(Audit(author_id=user.id, kind="anomaly_observation", target_id=anomaly_id, payload={"revision": p["revision"], "description": p["sheet"]["anomaly_note"], "priority": p["sheet"]["priority"]}))
    receipt = {"operation_id": oid, "inspection_id": item.id, "revision": p["revision"], "received_at": now(), "server_evaluation": evaluations[-1], "review": "NON_ESAMINATA"}
    db.add(SyncOperation(id=oid, user_id=user.id, digest=sha, receipt=receipt))
    db.commit()
    return receipt

@app.post("/api/sync")
def sync(body: Operation, auth=Depends(principal), db: Session = Depends(session)):
    try: return receive(body, *auth, db)
    except IntegrityError:
        db.rollback()
        old = db.get(SyncOperation, str(body.operation_id))
        if old and old.user_id == auth[0].id and old.digest == hashlib.sha256(canonical(body.model_dump(mode="json"))).hexdigest(): return old.receipt
        raise HTTPException(409, "Operazione concorrente: verificare revisione e riprovare")

@app.get("/api/inspections")
def inspections(area: str, gps: str | None = None, status: str | None = None, auth=Depends(principal), db: Session = Depends(session)):
    area_check(db, auth[0], area)
    result = rows(db, area)
    if auth[0].role == "operaio": result = [x for x in result if x["user"] == auth[0].username]
    return [x for x in result if (not gps or x["gps"] == gps) and (not status or x["status"] == status)]

@app.get("/api/inspections/{iid}")
def detail(iid: str, auth=Depends(principal), db: Session = Depends(session)):
    item = db.get(Inspection, iid)
    if not item: raise HTTPException(404)
    area_check(db, auth[0], item.area_id)
    if auth[0].role == "operaio" and item.user_id != auth[0].id: raise HTTPException(403)
    revisions = db.scalars(select(Revision).where(Revision.inspection_id == iid).order_by(Revision.number)).all()
    reference=next(p for p in db.get(Dataset,item.dataset_id).payload["points"] if p["id"]==item.manhole_id)
    return {"id": iid, "reference":reference, "revisions": [{"number": r.number, "author_id": r.author_id, "reason": r.reason, "received_at": r.received_at, "payload": r.payload, "server_evaluation": r.server_evaluation, "review": review_state(db, iid, r.number)} for r in revisions]}

@app.post("/api/inspections/{iid}/review")
def review(iid: str, body: ReviewInput, auth=Depends(principal), db: Session = Depends(session)):
    reviewer(auth[0]); item = db.get(Inspection, iid)
    if not item: raise HTTPException(404)
    area_check(db, auth[0], item.area_id)
    if not db.get(Revision, (iid, body.revision)): raise HTTPException(404, "Revisione non trovata")
    db.add(Audit(author_id=auth[0].id, kind="review", target_id=iid, payload=body.model_dump())); db.commit()
    return {"saved": True}

@app.get("/api/dashboard")
def dashboard(area: str, auth=Depends(principal), db: Session = Depends(session)):
    reviewer(auth[0]); area_check(db, auth[0], area)
    latest = db.scalar(select(Dataset).where(Dataset.area_id == area).order_by(Dataset.created_at.desc()))
    r = rows(db, area)
    labels={p["id"]: " / ".join([" | ".join(p["collectors"]),p["code"]]) for p in latest.payload["points"]} if latest else {}
    recorded = {x["manhole_id"] for x in r}
    ds = []
    for d in db.scalars(select(Deadline).where(Deadline.area_id == area)):
        visits = [x for x in r if x["deadline_id"] == d.id and x["status"] == "COMPLETO"]
        first = min((x["completed_at"] for x in visits), default=None)
        due = datetime.fromisoformat(d.due_at)
        ds.append({"id": d.id, "manhole_id": d.manhole_id, "manhole_label": labels.get(d.manhole_id,"Manufatto storico"), "due_at": d.due_at, "source": d.source,
                   "completion": first, "late": first is not None and datetime.fromisoformat(first) > due,
                   "outstanding": first is None and due < datetime.now(timezone.utc)})
    return {"without_records": [p for p in latest.payload["points"] if p["id"] not in recorded] if latest else [], "deadlines": ds,
            "gps_to_review": [x for x in r if x["gps"] != "COMPATIBILE"],
            "anomalies": [{"id": a.id, "inspection_id": a.inspection_id, "priority": a.priority, "description": a.description, "state": a.state,
                           "history": [{"at": h.at, "author": h.author_id, **h.payload} for h in db.scalars(select(Audit).where(Audit.target_id == a.id).order_by(Audit.at))]} for a in db.scalars(select(Anomaly).where(Anomaly.area_id == area))]}

@app.post("/api/anomalies/{aid}")
def anomaly(aid: str, body: AnomalyInput, auth=Depends(principal), db: Session = Depends(session)):
    reviewer(auth[0]); item = db.get(Anomaly, aid)
    if not item: raise HTTPException(404)
    area_check(db, auth[0], item.area_id)
    item.state = body.state
    db.add(Audit(author_id=auth[0].id, kind="anomaly_state", target_id=aid, payload=body.model_dump())); db.commit()
    return {"saved": True}

@app.post("/api/deadlines")
def deadline(body: DeadlineInput, auth=Depends(principal), db: Session = Depends(session)):
    reviewer(auth[0]); point = db.get(Manhole, str(body.manhole_id))
    if not point or not db.get(Company, str(body.company_id)): raise HTTPException(422, "Manufatto o impresa non trovati")
    area_check(db, auth[0], point.area_id)
    if body.due_at.tzinfo is None: raise HTTPException(422, "Fuso orario necessario")
    row = Deadline(area_id=point.area_id, manhole_id=point.id, company_id=str(body.company_id), due_at=body.due_at.astimezone(timezone.utc).isoformat(), source=body.source)
    db.add(row); db.flush()
    db.add(Audit(author_id=auth[0].id, kind="deadline_created", target_id=row.id, payload=body.model_dump(mode="json"))); db.commit()
    return {"id": row.id}

@app.get("/api/reference-options")
def reference_options(area: str, auth=Depends(principal), db: Session = Depends(session)):
    reviewer(auth[0]); area_check(db,auth[0],area)
    companies=db.scalars(select(Company).join(User,User.company_id==Company.id).join(Grant,Grant.user_id==User.id).where(Grant.area_id==area).distinct()).all()
    return {"companies":[{"id":c.id,"name":c.name} for c in companies]}

@app.post("/api/rules")
def rule(body: RuleInput, auth=Depends(principal), db: Session = Depends(session)):
    administrator(auth[0])
    if db.get(Rule, body.version): raise HTTPException(409, "Versione immutabile: usare un nuovo identificativo")
    db.add(Rule(id=body.version, parameters=body.model_dump(), author_id=auth[0].id)); db.commit()
    return {"saved": True}

@app.get("/api/users")
def users(auth=Depends(principal), db: Session = Depends(session)):
    administrator(auth[0])
    return {"users": [{"id": u.id, "username": u.username, "role": u.role, "active": u.active, "company_id": u.company_id, "areas": allowed(db,u)} for u in db.scalars(select(User))],
            "companies": [{"id": c.id, "name": c.name} for c in db.scalars(select(Company))], "areas": [{"id": a.id, "name": a.name} for a in db.scalars(select(Area))]}

@app.post("/api/users")
def create_user(body: UserInput, auth=Depends(principal), db: Session = Depends(session)):
    administrator(auth[0])
    if not db.get(Company, str(body.company_id)) or any(not db.get(Area, a) for a in body.areas): raise HTTPException(422, "Impresa o area inesistente")
    if db.scalar(select(User).where(User.username == body.username)): raise HTTPException(409, "Nome già presente")
    u = User(username=body.username, password_hash=passwords.hash(body.password), role=body.role, company_id=str(body.company_id))
    db.add(u); db.flush()
    for a in set(body.areas): db.add(Grant(user_id=u.id, area_id=a))
    db.add(Audit(author_id=auth[0].id, kind="user_created", target_id=u.id, payload={"role": body.role, "areas": body.areas})); db.commit()
    return {"id": u.id}

@app.post("/api/users/{user_id}/disable")
def disable(user_id: str, auth=Depends(principal), db: Session = Depends(session)):
    administrator(auth[0]); u = db.get(User, user_id)
    if not u: raise HTTPException(404)
    if u.id == auth[0].id: raise HTTPException(422, "Non disabilitare la propria sessione")
    u.active = False
    db.add(Audit(author_id=auth[0].id, kind="user_disabled", target_id=u.id, payload={})); db.commit()
    return {"saved": True}

@app.put("/api/users/{user_id}/grants")
def update_grants(user_id: str, body: GrantsInput, auth=Depends(principal), db: Session = Depends(session)):
    administrator(auth[0]); u=db.get(User,user_id)
    if not u: raise HTTPException(404)
    if any(not db.get(Area,a) for a in body.areas): raise HTTPException(422,"Area inesistente")
    previous=allowed(db,u)
    for grant in db.scalars(select(Grant).where(Grant.user_id==u.id)):db.delete(grant)
    db.flush()
    for a in set(body.areas):db.add(Grant(user_id=u.id,area_id=a))
    db.add(Audit(author_id=auth[0].id,kind="grants_updated",target_id=u.id,payload={"before":previous,"after":sorted(set(body.areas))}));db.commit()
    return {"saved":True}

@app.post("/api/users/{user_id}/enable")
def enable(user_id: str, body: ActionNote, auth=Depends(principal), db: Session = Depends(session)):
    administrator(auth[0]); u = db.get(User, user_id)
    if not u: raise HTTPException(404)
    u.active = True
    db.add(Audit(author_id=auth[0].id, kind="user_reenabled", target_id=u.id, payload={"reason": body.note})); db.commit()
    return {"saved": True}

@app.post("/api/reports")
def report(area: str, year: int, quarter: int = Query(ge=1, le=4), synthetic: bool = False, auth=Depends(principal), db: Session = Depends(session)):
    reviewer(auth[0]); area_check(db, auth[0], area)
    a = db.get(Area, area)
    if not a or a.synthetic != synthetic: raise HTTPException(422, "Scegliere esplicitamente il rapporto sintetico/operativo corretto")
    try: s = snapshot(db, area, year, quarter, synthetic)
    except ValueError as exc: raise HTTPException(422, str(exc))
    rid = uid(); s["report_id"] = rid; s["version"] = rid
    db.add(Report(id=rid, author_id=auth[0].id, area_id=area, period=s["period"], extracted_at=s["extracted_at"], snapshot=s)); db.commit()
    return {"id": rid, "snapshot": s}

@app.get("/api/reports")
def report_list(area: str, auth=Depends(principal), db: Session = Depends(session)):
    reviewer(auth[0]); area_check(db, auth[0], area)
    return [{"id": r.id, "period": r.period, "extracted_at": r.extracted_at} for r in db.scalars(select(Report).where(Report.area_id == area).order_by(Report.extracted_at.desc()))]

@app.get("/api/reports/{rid}/{fmt}")
def download_report(rid: str, fmt: str, auth=Depends(principal), db: Session = Depends(session)):
    reviewer(auth[0]); r = db.get(Report, rid)
    if not r: raise HTTPException(404)
    area_check(db, auth[0], r.area_id)
    if fmt == "csv": data, mime = export_csv(r.snapshot), "text/csv; charset=utf-8"
    elif fmt == "xlsx": data, mime = export_xlsx(r.snapshot), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    elif fmt == "json": data, mime = canonical(r.snapshot), "application/json"
    else: raise HTTPException(404)
    return Response(data, media_type=mime, headers={"Content-Disposition": f'attachment; filename="{r.period}-{r.id}.{fmt}"'})
