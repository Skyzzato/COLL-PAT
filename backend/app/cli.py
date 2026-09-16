import argparse
import getpass
import json
import os
from pathlib import Path
from sqlalchemy import select
from .db import Session
from .models import Company, Area, User, Grant, Rule
from .auth import passwords
from .gis import import_zip

def main():
    parser = argparse.ArgumentParser(description="Amministrazione locale Collettori PAT")
    sub = parser.add_subparsers(dest="cmd", required=True)
    boot = sub.add_parser("bootstrap")
    boot.add_argument("--username", required=True)
    boot.add_argument("--company", required=True)
    boot.add_argument("--area", required=True)
    boot.add_argument("--area-name", required=True)
    boot.add_argument("--synthetic", action="store_true")
    imp = sub.add_parser("import-gis")
    imp.add_argument("zip")
    imp.add_argument("mapping")
    export = sub.add_parser("export-data")
    export.add_argument("destination")
    recover = sub.add_parser("recover")
    recover.add_argument("file")
    recover.add_argument("--approver",required=True)
    recover.add_argument("--reason",required=True)
    args = parser.parse_args()
    with Session() as db:
        if args.cmd == "bootstrap":
            if db.scalar(select(User).where(User.username == args.username)): raise SystemExit("Utente esistente: nessuna modifica")
            password = getpass.getpass("Password amministratore (almeno 12 caratteri): ")
            if len(password) < 12: raise SystemExit("Password troppo breve")
            if password != getpass.getpass("Ripetere password: "): raise SystemExit("Password diverse")
            company = db.scalar(select(Company).where(Company.name == args.company))
            if not company: company = Company(name=args.company); db.add(company); db.flush()
            area = db.get(Area, args.area)
            if not area: db.add(Area(id=args.area, name=args.area_name, synthetic=args.synthetic)); db.flush()
            elif area.synthetic != args.synthetic: raise SystemExit("Tipo area diverso")
            user = User(username=args.username, company_id=company.id, role="admin", password_hash=passwords.hash(password))
            db.add(user); db.flush(); db.add(Grant(user_id=user.id, area_id=args.area))
            path = Path(os.getenv("SHARED_DIR", "../shared")) / "gps-rule.json"
            rule = json.loads(path.read_text())
            if not db.get(Rule, rule["version"]): db.add(Rule(id=rule["version"], parameters=rule))
            db.commit()
            print("Amministratore individuale creato. Configurare gli operai nel portale.")
        elif args.cmd == "import-gis":
            result = import_zip(db, args.zip, json.loads(Path(args.mapping).read_text(encoding="utf-8")))
            print(json.dumps(result, ensure_ascii=False, indent=2))
        elif args.cmd == "recover":
            from types import SimpleNamespace
            from .main import receive
            from .models import Dataset, Audit
            from .schemas import Operation
            from .auth import area_check
            approver=db.scalar(select(User).where(User.username == args.approver,User.active == True,User.role == "admin"))
            password=getpass.getpass("Password dell'amministratore che autorizza il recupero: ")
            if not approver or not passwords.verify(password,approver.password_hash):raise SystemExit("Autorizzazione amministrativa non valida")
            data=json.loads(Path(args.file).read_text(encoding="utf-8"))
            if data.get("format")!="collettori-recovery-1":raise SystemExit("Formato non supportato")
            for raw in data.get("operations",[]):
                op=Operation.model_validate(raw)
                author=db.get(User,str(op.inspection.user_id))
                dataset=db.get(Dataset,str(op.inspection.dataset_id))
                if not author or not dataset:raise SystemExit("Autore o dataset non disponibile: verificare il backup")
                area_check(db,approver,dataset.area_id)
                db.add(Audit(author_id=approver.id,kind="controlled_recovery",target_id=str(op.operation_id),payload={"reason":args.reason,"original_author":author.id}))
                try:
                    receipt=receive(op,author,SimpleNamespace(device_id=op.inspection.device_id),db)
                    db.commit()
                    print("Ricevuta:",receipt["operation_id"],receipt["inspection_id"])
                except Exception:
                    db.rollback()
                    raise
            print("Bozze nel file (non dichiarate complete, non importate come prestazioni):",len(data.get("drafts",[])))
        else:
            from .db import Base
            import zipfile
            from .gis import canonical
            target=Path(args.destination)
            if target.exists(): raise SystemExit("Destinazione esistente: scegliere un nuovo nome")
            with zipfile.ZipFile(target,"x",zipfile.ZIP_DEFLATED) as archive:
                for table in Base.metadata.sorted_tables:
                    if table.name == "login_sessions": continue
                    records=[]
                    for row in db.execute(select(table)).mappings():
                        records.append({k:v for k,v in row.items() if k != "password_hash"})
                    archive.writestr(table.name+".json",canonical(records))
                archive.writestr("README.txt","Collettori PAT 0.1. Esportazione JSON UTF-8: UUID e relazioni preservati. Dataset contengono coordinate WGS84 e versioni storiche. Nessuna password o token. Nessun allegato fotografico nella v0.1.")
            print("Esportazione creata:",target)

if __name__ == "__main__": main()
