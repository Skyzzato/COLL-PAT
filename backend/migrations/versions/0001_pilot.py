"""Initial pilot schema and PostGIS snapshot geometry index."""
from alembic import op
import sqlalchemy as sa
from app.db import Base
from app import models
revision = "0001"
down_revision = None

def upgrade():
    bind = op.get_bind()
    Base.metadata.create_all(bind)
    if bind.dialect.name == "postgresql":
        op.execute("CREATE EXTENSION IF NOT EXISTS postgis")
        op.execute("CREATE TABLE reference_geometries (dataset_id varchar(36) REFERENCES datasets(id), entity_id varchar(36), geom geometry(Geometry,4326) NOT NULL, PRIMARY KEY(dataset_id,entity_id))")
        op.execute("CREATE INDEX reference_geom_gist ON reference_geometries USING gist(geom)")

def downgrade():
    raise RuntimeError("Downgrade distruttivo disabilitato: ripristinare un backup verificato")
