from datetime import datetime, timezone
from uuid import uuid4
from sqlalchemy import String, Text, JSON, Boolean, ForeignKey, UniqueConstraint, Integer
from sqlalchemy.orm import Mapped, mapped_column
from .db import Base

def uid(): return str(uuid4())
def now(): return datetime.now(timezone.utc).isoformat()

class Company(Base):
    __tablename__ = "companies"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    name: Mapped[str]

class Area(Base):
    __tablename__ = "areas"
    id: Mapped[str] = mapped_column(String(80), primary_key=True)
    name: Mapped[str]
    synthetic: Mapped[bool] = mapped_column(Boolean, default=False)

class User(Base):
    __tablename__ = "users"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    username: Mapped[str] = mapped_column(String(120), unique=True)
    password_hash: Mapped[str]
    role: Mapped[str]
    company_id: Mapped[str] = mapped_column(ForeignKey("companies.id"))
    active: Mapped[bool] = mapped_column(Boolean, default=True)

class Grant(Base):
    __tablename__ = "area_grants"
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), primary_key=True)
    area_id: Mapped[str] = mapped_column(ForeignKey("areas.id"), primary_key=True)

class LoginSession(Base):
    __tablename__ = "login_sessions"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    device_id: Mapped[str]
    access_hash: Mapped[str] = mapped_column(String(64), unique=True)
    refresh_hash: Mapped[str] = mapped_column(String(64), unique=True)
    access_until: Mapped[str]
    refresh_until: Mapped[str]

class Dataset(Base):
    __tablename__ = "datasets"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    area_id: Mapped[str] = mapped_column(ForeignKey("areas.id"), index=True)
    created_at: Mapped[str] = mapped_column(default=now)
    payload: Mapped[dict] = mapped_column(JSON)
    source_path: Mapped[str]
    report: Mapped[dict] = mapped_column(JSON)
    sha256: Mapped[str]

class Collector(Base):
    __tablename__ = "collectors"
    __table_args__ = (UniqueConstraint("area_id", "code"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    area_id: Mapped[str] = mapped_column(ForeignKey("areas.id"))
    code: Mapped[str]

class Manhole(Base):
    __tablename__ = "manholes"
    __table_args__ = (UniqueConstraint("area_id", "source_key"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    area_id: Mapped[str] = mapped_column(ForeignKey("areas.id"), index=True)
    source_key: Mapped[str]

class Segment(Base):
    __tablename__ = "segments"
    __table_args__ = (UniqueConstraint("area_id", "source_key"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    area_id: Mapped[str] = mapped_column(ForeignKey("areas.id"))
    collector_id: Mapped[str] = mapped_column(ForeignKey("collectors.id"))
    source_key: Mapped[str]

class Connection(Base):
    __tablename__ = "connections"
    dataset_id: Mapped[str] = mapped_column(ForeignKey("datasets.id"), primary_key=True)
    segment_id: Mapped[str] = mapped_column(ForeignKey("segments.id"), primary_key=True)
    manhole_id: Mapped[str] = mapped_column(ForeignKey("manholes.id"), primary_key=True)
    endpoint: Mapped[str] = mapped_column(String(10), primary_key=True)

class Rule(Base):
    __tablename__ = "rules"
    id: Mapped[str] = mapped_column(String(80), primary_key=True)
    created_at: Mapped[str] = mapped_column(default=now)
    author_id: Mapped[str | None] = mapped_column(ForeignKey("users.id"), nullable=True)
    parameters: Mapped[dict] = mapped_column(JSON)

class Inspection(Base):
    __tablename__ = "inspections"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    manhole_id: Mapped[str] = mapped_column(ForeignKey("manholes.id"), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    company_id: Mapped[str] = mapped_column(ForeignKey("companies.id"))
    area_id: Mapped[str] = mapped_column(ForeignKey("areas.id"), index=True)
    dataset_id: Mapped[str] = mapped_column(ForeignKey("datasets.id"))
    device_id: Mapped[str]
    synthetic: Mapped[bool]
    current_revision: Mapped[int] = mapped_column(Integer, default=1)
    received_at: Mapped[str] = mapped_column(default=now)

class Revision(Base):
    __tablename__ = "revisions"
    inspection_id: Mapped[str] = mapped_column(ForeignKey("inspections.id"), primary_key=True)
    number: Mapped[int] = mapped_column(Integer, primary_key=True)
    author_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    reason: Mapped[str]
    received_at: Mapped[str] = mapped_column(default=now)
    payload: Mapped[dict] = mapped_column(JSON)
    server_evaluation: Mapped[dict] = mapped_column(JSON)

class LocationEvent(Base):
    __tablename__ = "location_events"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    inspection_id: Mapped[str] = mapped_column(ForeignKey("inspections.id"), index=True)
    payload: Mapped[dict] = mapped_column(JSON)
    server_evaluation: Mapped[dict] = mapped_column(JSON)

class SyncOperation(Base):
    __tablename__ = "sync_operations"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    digest: Mapped[str]
    receipt: Mapped[dict] = mapped_column(JSON)

class Anomaly(Base):
    __tablename__ = "anomalies"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    inspection_id: Mapped[str] = mapped_column(ForeignKey("inspections.id"), index=True)
    area_id: Mapped[str] = mapped_column(ForeignKey("areas.id"), index=True)
    priority: Mapped[str]
    description: Mapped[str]
    state: Mapped[str] = mapped_column(default="APERTA")

class Audit(Base):
    __tablename__ = "audit"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    author_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    kind: Mapped[str]
    target_id: Mapped[str]
    at: Mapped[str] = mapped_column(default=now)
    payload: Mapped[dict] = mapped_column(JSON)

class Deadline(Base):
    __tablename__ = "deadlines"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    area_id: Mapped[str] = mapped_column(ForeignKey("areas.id"))
    manhole_id: Mapped[str] = mapped_column(ForeignKey("manholes.id"), index=True)
    company_id: Mapped[str] = mapped_column(ForeignKey("companies.id"))
    due_at: Mapped[str]
    source: Mapped[str]
    created_at: Mapped[str] = mapped_column(default=now)

class Report(Base):
    __tablename__ = "reports"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    author_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    area_id: Mapped[str] = mapped_column(ForeignKey("areas.id"))
    period: Mapped[str]
    extracted_at: Mapped[str] = mapped_column(default=now)
    snapshot: Mapped[dict] = mapped_column(JSON)
