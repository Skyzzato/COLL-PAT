FROM python:3.12.14-slim@sha256:78387bc3881b8273120a12ebe6c1ab22b018ccc2c9adf565ae1ac9b536e184ea
WORKDIR /srv
COPY backend/requirements.lock /srv/requirements.lock
RUN pip install --no-cache-dir -r requirements.lock
COPY backend /srv
COPY shared /shared
RUN useradd --system --uid 10001 pilot && mkdir -p /srv/runtime && chown -R pilot:pilot /srv/runtime
USER pilot
ENV SHARED_DIR=/shared
CMD ["sh", "-c", "alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port 8000"]
