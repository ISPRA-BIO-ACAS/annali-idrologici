# HydroServer Annals ingestor: runs main.py to ingest Annals/ISPRA data into HydroServer.
# Build-time: create annali_ispra package so "from annali_ispra.ingest import AnnaliIngestor" works.
FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY ingest.py skos_client.py main.py ./
RUN mkdir -p annali_ispra && touch annali_ispra/__init__.py && mv ingest.py skos_client.py annali_ispra/

# HYDROSERVER_URL, HYDROSERVER_EMAIL, HYDROSERVER_PASSWORD, HS_DATA_DIR, HS_WORKSPACE_NAME, HS_FAST
ENV HYDROSERVER_URL="http://host.docker.internal:8000" \
    HS_DATA_DIR="/data" \
    HS_WORKSPACE_NAME="Annali" \
    HS_FAST="true"

ENTRYPOINT ["python", "main.py"]
