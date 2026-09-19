from datetime import datetime
from typing import Literal
from uuid import UUID
from pydantic import BaseModel, ConfigDict, Field, model_validator

class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

class Login(Strict):
    username: str
    password: str
    device_id: str = Field(min_length=8, max_length=120)

class Refresh(Strict):
    refresh_token: str

class Event(Strict):
    id: UUID
    inspection_id: UUID
    manhole_id: UUID
    user_id: UUID
    device_id: str
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)
    accuracy_m: float | None = Field(default=None, ge=0)
    age_s: float | None = Field(default=None, ge=0)
    requested_at: datetime
    acquired_at: datetime
    provider: str | None = None
    permission: Literal["PRECISE", "APPROXIMATE", "DENIED"]
    mock: bool | None = None
    error: str | None = None
    app_version: Literal["0.1", "0.11", "0.12"]
    dataset_id: UUID
    rule_version: str
    local_evaluation: dict

    @model_validator(mode="after")
    def valid(self):
        if (self.latitude is None) != (self.longitude is None): raise ValueError("Coordinate incomplete")
        if self.requested_at.tzinfo is None or self.acquired_at.tzinfo is None: raise ValueError("Fuso orario obbligatorio")
        return self

Observation = Literal["REGOLARE", "ANOMALO", "NON_OSSERVABILE", "NON_APPLICABILE", "NON_VERIFICATO"]
class Sheet(Strict):
    accessible: bool | None = None
    opened: bool | None = None
    no_open_reason: str = ""
    unsafe: bool = False
    cover: Observation = "NON_VERIFICATO"
    deposits: Observation = "NON_VERIFICATO"
    flow: Observation = "NON_VERIFICATO"
    walls: Observation = "NON_VERIFICATO"
    damage: Observation = "NON_VERIFICATO"
    cleaning: bool | None = None
    closure: Observation = "NON_VERIFICATO"
    restored: Observation = "NON_VERIFICATO"
    anomaly_note: str = ""
    priority: Literal["BASSA", "MEDIA", "ALTA", "URGENTE"] = "MEDIA"
    technical_value: str = ""
    technical_origin: Literal["OSSERVATO", "MISURATO", "DOCUMENTALE", "IPOTIZZATO", "NON_NOTO"] = "NON_NOTO"
    notes: str = ""
    exception_reason: str = ""
    map_position_wrong: bool = False

class InspectionPayload(Strict):
    id: UUID
    manhole_id: UUID
    dataset_id: UUID
    device_id: str
    user_id: UUID
    selection_method: Literal["MAP", "LIST"]
    started_at: datetime
    completed_at: datetime
    status: Literal["COMPLETO", "PARZIALE", "IMPEDITO"]
    sheet_version: Literal["sheet-1"] = "sheet-1"
    sheet: Sheet
    events: list[Event] = Field(min_length=1, max_length=100)
    deadline_id: UUID | None = None
    revision: int = Field(ge=1)
    revision_reason: str = ""
    revised_at: datetime | None = None

    @model_validator(mode="after")
    def valid(self):
        if self.started_at.tzinfo is None or self.completed_at.tzinfo is None: raise ValueError("Fuso orario obbligatorio")
        if self.revised_at is not None and self.revised_at.tzinfo is None: raise ValueError("Fuso orario revisione obbligatorio")
        if self.completed_at < self.started_at: raise ValueError("Completamento precedente alla bozza")
        s = self.sheet
        if s.accessible is None or s.opened is None: raise ValueError("Specificare accesso e apertura")
        if not s.opened and not s.no_open_reason.strip(): raise ValueError("Motivare la mancata apertura")
        if (s.unsafe or not s.accessible) and self.status != "IMPEDITO": raise ValueError("Impedimento distinto dal controllo completo")
        if self.status == "COMPLETO":
            if not s.opened or any(getattr(s, k) == "NON_VERIFICATO" for k in ["cover", "deposits", "flow", "walls", "damage", "closure", "restored"]):
                raise ValueError("Controllo completo: apertura e osservazioni obbligatorie")
        if any(getattr(s, k) == "ANOMALO" for k in ["cover", "deposits", "flow", "walls", "damage", "closure", "restored"]) and not s.anomaly_note.strip():
            raise ValueError("Descrivere l'anomalia")
        if self.revision > 1 and not self.revision_reason.strip(): raise ValueError("Motivo della revisione obbligatorio")
        if len({e.id for e in self.events}) != len(self.events): raise ValueError("Evento duplicato")
        for e in self.events:
            if (e.inspection_id, e.manhole_id, e.user_id, e.dataset_id, e.device_id) != (self.id, self.manhole_id, self.user_id, self.dataset_id, self.device_id):
                raise ValueError("Evento non associato alla visita")
        return self

class Operation(Strict):
    operation_id: UUID
    inspection: InspectionPayload

class ReviewInput(Strict):
    revision: int
    state: Literal["NON_ESAMINATA", "VERIFICATA_DOCUMENTALMENTE", "INTEGRAZIONE_RICHIESTA"]
    note: str = Field(min_length=1)

class AnomalyInput(Strict):
    state: Literal["APERTA", "CHIUSA"]
    note: str = Field(min_length=1)

class ActionNote(Strict):
    note: str = Field(min_length=1)

class GrantsInput(Strict):
    areas: list[str]

class UserInput(Strict):
    username: str = Field(min_length=3, max_length=120)
    password: str = Field(min_length=12, max_length=256)
    role: Literal["operaio", "verificatore", "admin"]
    company_id: UUID
    areas: list[str]

class DeadlineInput(Strict):
    manhole_id: UUID
    company_id: UUID
    due_at: datetime
    source: str = Field(min_length=1)

class RuleInput(Strict):
    version: str = Field(pattern=r"^[a-zA-Z0-9_-]{1,80}$")
    radius_m: float = Field(gt=0, le=1000)
    max_accuracy_m: float = Field(gt=0, le=1000)
    max_age_s: float = Field(gt=0, le=300)
    timeout_s: float = Field(gt=0, le=120)
    max_map_uncertainty_m: float = Field(gt=0, le=1000)
    earth_radius_m: Literal[6371008.8] = 6371008.8
