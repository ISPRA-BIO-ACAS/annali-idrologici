# HydroServer Annals ingestor

Python ingestor that loads prepared Annals CSV data into [HydroServer](https://github.com/hydroserver2).

## Docker

Built and run via `docker-compose-annals-hs-ingestor.yml` at the repository root. See the root [README.md](../README.md).

### Target HydroServer URL

Set **`HYDROSERVER_URL`** to choose which HydroServer instance to ingest into (local stack default, or any reachable server):

```bash
# Local compose stack (default)
docker compose -f ../docker-compose-annals-hs-ingestor.yml up --build

# Different / remote HydroServer
HYDROSERVER_URL=https://my-hydroserver.example.com \
HYDROSERVER_EMAIL=you@example.com \
HYDROSERVER_PASSWORD=secret \
docker compose -f ../docker-compose-annals-hs-ingestor.yml up --build
```

| Variable | Purpose |
|----------|---------|
| `HYDROSERVER_URL` | HydroServer base URL (default: `http://host.docker.internal:8000`) |
| `HYDROSERVER_EMAIL` | Account email for API auth |
| `HYDROSERVER_PASSWORD` | Account password |
| `HS_DATA_DIR` | Annals data root (default: `/data`) |
| `HS_WORKSPACE_NAME` | Workspace name to create/use (default: `Annali`) |
| `HS_FAST` | Smoke-test mode with a reduced dataset (default: `false` in compose) |

## License

Python sources in this module are licensed under **GNU AGPL v3** (author:
**CNR-ITIAm / ESSI-Lab**; see [`../CITATION-software.cff`](../CITATION-software.cff)).
The Annals **dataset** under `../data/` is licensed separately under **CC BY 4.0**
and attributed to **ISPRA BIO-ACAS** (see [`../LICENSE`](../LICENSE) and
[`../CITATION.cff`](../CITATION.cff)).
