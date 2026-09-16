import hashlib
import os
import secrets
from datetime import datetime, timedelta, timezone
from fastapi import Depends, HTTPException
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from pwdlib import PasswordHash
from sqlalchemy import select
from sqlalchemy.orm import Session
from .db import session
from .models import User, LoginSession, Grant, now

passwords = PasswordHash.recommended()
bearer = HTTPBearer(auto_error=False)
def digest(s): return hashlib.sha256(s.encode()).hexdigest()
def until(seconds): return (datetime.now(timezone.utc)+timedelta(seconds=seconds)).isoformat()

def issue(db, user, device, existing=None):
    access = secrets.token_urlsafe(32)
    refresh = secrets.token_urlsafe(48)
    row = existing or LoginSession(user_id=user.id, device_id=device, refresh_hash=digest(refresh), refresh_until=until(30*86400))
    row.access_hash, row.access_until = digest(access), until(900)
    db.add(row)
    db.commit()
    return {"access_token": access, "refresh_token": refresh if not existing else None, "access_until": row.access_until,
            "offline_until": until(int(os.getenv("OFFLINE_HOURS", "72"))*3600), "server_time": now(),
            "user_id": user.id, "username": user.username, "company_id": user.company_id, "role": user.role,
            "areas": db.scalars(select(Grant.area_id).where(Grant.user_id == user.id)).all()}

def principal(credentials: HTTPAuthorizationCredentials = Depends(bearer), db: Session = Depends(session)):
    if not credentials: raise HTTPException(401, "Autenticazione richiesta")
    login = db.scalar(select(LoginSession).where(LoginSession.access_hash == digest(credentials.credentials)))
    if not login or login.access_until < now(): raise HTTPException(401, "Sessione scaduta")
    user = db.get(User, login.user_id)
    if not user or not user.active: raise HTTPException(401, "Utente disabilitato")
    return user, login

def allowed(db, user): return list(db.scalars(select(Grant.area_id).where(Grant.user_id == user.id)))
def area_check(db, user, area_id):
    if area_id not in allowed(db, user): raise HTTPException(403, "Ambito non autorizzato")
def reviewer(user):
    if user.role not in ["admin", "verificatore"]: raise HTTPException(403, "Ruolo non autorizzato")
def administrator(user):
    if user.role != "admin": raise HTTPException(403, "Amministratore richiesto")
