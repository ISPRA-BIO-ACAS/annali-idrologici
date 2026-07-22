# Ingestor

Maven project for ingesting Annals (ISPRA) hydrological data into a FROST Server (OGC SensorThings API). It provides a shared **FROSTClient** library, STA folder mapping/upload utilities, and the Annals ingestor.

## Structure

- **`eu.flora.essi.frost`** – Shared FROST client and SensorThings entity wrappers:
  - `FROSTClient` – CRUD and batch operations for Things, Datastreams, Observations, Sensors, ObservedProperties, Locations, FeaturesOfInterest
  - Entity classes: `Thing`, `Datastream`, `Observation`, `Sensor`, `ObservedProperty`, `Location`, `FeatureOfInterest`
  - `FilterBuilder`, `PagedResult`
  - Example / utility classes: `FROSTClientAdvancedExample`, `FROSTClientFilterExample`,
    `STAEndpointStats` (endpoint summary: entity counts, Locations bbox, phenomenonTime
    period stats, ObservedProperties), `RemoveDatastreamsByObservedPropertyName`

- **`eu.flora.essi.ingestor.annals`** – Annals (ISPRA) ingestor:
  - `AnnalsIngestor` – prepares raw data, ingests Annals CSV data into FROST
  - `AnnalsDataPreparer` – extracts ZIP archives and sorts `OSSERVAZIONI` CSV files into `processed/`
  - Helpers: `CSVTable`, `Compartments`, `Stations`, `TimeSeries`, `GeneralMetadata`, `ObservedProperties`, `Instruments`, `UnitsOfMeasurement`, `EditorOrganizations`, `QualityFlags`, `AnnalsToStaFolderWriter`

- **`eu.flora.essi.ingestor.sta`** – STA folder upload utilities:
  - `STAtoFrostUploader` – uploads mapped STA JSON folders to FROST
  - `DeterministicIdGenerator` – stable entity IDs for idempotent uploads
  - `ObservationUploadStrategy` – duplicate-handling options during upload

## Data layout

Raw downloads live in `data/` (ZIP archives plus shared reference CSV files at the root). The prepare step writes extracted and sorted CSV files to `data/processed/`. Mapping writes STA JSON to `data/processed/sta/`.

| Path | Used by |
|------|---------|
| `data/` | Raw ZIPs and reference CSVs (committed) |
| `data/processed/` | Prepared regional CSVs (shared by FROST map and HydroServer ingest) |
| `data/processed/sta/` | Mapped STA JSON (FROST upload only) |

## Build

```bash
mvn compile
```

## License

Java sources in this module are licensed under **GNU AGPL v3** (author:
**CNR-ITIAm / ESSI-Lab**; see [`../CITATION-software.cff`](../CITATION-software.cff)).
The Annals **dataset** under `../data/` is licensed separately under **CC BY 4.0**
and attributed to **ISPRA BIO-ACAS** (see [`../LICENSE`](../LICENSE) and
[`../CITATION.cff`](../CITATION.cff)).

Headers are managed with the [license-maven-plugin](https://github.com/mathieucarbou/license-maven-plugin):

```bash
# Add or update headers in src/**/*.java
mvn license:format

# Verify headers (also runs on mvn verify)
mvn license:check
```

The header template is `license/AGPL-3-header.txt`. Set the copyright holder via the `license.owner` property in `pom.xml`.

## Run STA endpoint statistics

Main class: **`eu.flora.essi.frost.STAEndpointStats`**

Prints entity counts, Locations N-W-S-E bbox, Datastream `phenomenonTime` period stats
(shortest / longest / median), and all ObservedProperties for a given FROST/STA root URL:

```bash
mvn -q exec:java -Dexec.mainClass="eu.flora.essi.frost.STAEndpointStats" \
  -Dexec.args="https://emr-data.ispra.essi-lab.eu/FROST-Server/v1.1/"
```

Optional flags:
- `--skip-observations-count` — Observations `$count` can be slow on large deployments
- `--skip-observations-per-datastream` — skip min/median/max observations per Datastream (one `$count` per Datastream)
- `--no-log-requests` — disable per-request `GET`/`GOT` logging (enabled by default)

## Run Annals ingestor

Main class: **`eu.flora.essi.ingestor.annals.AnnalsIngestor`**

Set **`FROST_BASE_URL`** and point **`ANNALS_DATA_FOLDER`** at the raw `data/` directory:

```bash
mvn exec:java -Dexec.mainClass="eu.flora.essi.ingestor.annals.AnnalsIngestor"
```

Environment variables:

### Prepare and map (`AnnalsPrepareAndMap`, `docker-compose-annals-prepare.yml`)

| Variable | Purpose |
|----------|---------|
| `ANNALS_DATA_FOLDER` | Raw data folder (default: `data`) |
| `ANNALS_LOCAL_FOLDER` | Working folder for processed data and STA output (default: `<ANNALS_DATA_FOLDER>/processed`) |
| `ANNALS_PREPARE` | Extract ZIPs and sort `OSSERVAZIONI` CSVs into `processed/` (default: `true`) |
| `ANNALS_PREPARE_FORCE` | Re-extract and re-sort even if outputs already exist (default: `false`) |
| `ANNALS_MAP` | Map CSV → STA folder (default: `true`) |
| `ANNALS_FAST` | Stop mapping after 1000 observations (smoke test; default: `false`) |
| `ANNALS_MAX_OBSERVATIONS_PER_BATCH` | Observations per batch file written under `sta/` (default: `1000`; values above ~1000 may fail on the demo FROST Docker stack) |

### Upload to FROST (`AnnalsIngestor`, `docker-compose-annals-frost-ingestor.yml`)

| Variable | Purpose |
|----------|---------|
| `FROST_BASE_URL` | Full SensorThings root URL including `/FROST-Server/v1.1/` (required) |
| `ANNALS_DATA_FOLDER` | Raw data folder (reference CSVs; default: `data`) |
| `ANNALS_LOCAL_FOLDER` | Folder containing mapped STA data (default: `<ANNALS_DATA_FOLDER>/processed`) |
| `ANNALS_PREPARE` | Extract ZIPs and sort CSVs (default: `false`; use prepare compose instead) |
| `ANNALS_MAP` | Map CSV → STA folder (default: `false`; use prepare compose instead) |
| `ANNALS_UPLOAD` | Upload STA data to FROST (default: `true`) |
| `ANNALS_UPLOAD_STRATEGY` | Duplicate handling: `NONE`, `DELETE_BEFORE_UPLOAD`, `DETERMINISTIC_ID` (default: `DETERMINISTIC_ID`) |
| `ANNALS_UPLOAD_PARALLELISM` | Parallel datastream observation uploads (default: `16` in Docker Hub / compose; Java fallback `8`) |
| `ANNALS_BATCH_UPLOAD_TIMEOUT_MINUTES` | End-to-end timeout per `$batch` POST, including FROST processing time (default: `120`) |
| `ANNALS_BATCH_VERIFY_TIMEOUT_SECONDS` | Max wait while polling observation count after a batch POST (default: `600`) |
| `ANNALS_BATCH_VERIFY_POLL_INTERVAL_MS` | Interval between count polls after a batch POST (default: `2000`) |
| `ANNALS_UPLOAD_VERBOSE` | Detailed per-datastream/batch upload logs (default: `false`) |
| `ANNALS_USE_GZIP` | Gzip-compress HTTP request bodies (default: `false`) |
| `ANNALS_LOG_REQUESTS` | Log every FROST HTTP request (default: `false`) |

## Docker

Multi-stage **`Dockerfile`**: Maven build, then JRE runtime. Default **`entrypoint.sh`** downloads Annals data when `/data` is empty, then runs **`AnnalsIngestor`** with prepare + map + upload enabled (bring-your-own-FROST / Docker Hub mode).

The repository root Compose files override the entrypoint and mount `./data`, so the split prepare / upload workflow is unchanged:

- **`docker-compose-annals-prepare.yml`** – extract ZIPs, sort CSVs, map to STA (`./data` → `data/processed/` and `data/processed/sta/`)
- **`docker-compose-frost.yml`** – PostGIS + FROST Server (long-running)
- **`docker-compose-annals-frost-ingestor.yml`** – upload mapped STA data to FROST
- **`docker-compose-hydroserver.yml`** – HydroServer + PostgreSQL (long-running)
- **`docker-compose-annals-hs-ingestor.yml`** – upload prepared CSV data to HydroServer

See the root [README.md](../README.md).

### Bring your own FROST (Docker Hub UX)

```bash
docker build -t isprabioacas/annals-frost-ingestor:latest .

docker run --rm \
  -e FROST_BASE_URL=https://my-frost.example.com/FROST-Server/v1.1/ \
  -e ANNALS_MAX_OBSERVATIONS_PER_BATCH=1000 \
  -e ANNALS_UPLOAD_PARALLELISM=16 \
  -v annals-data:/data \
  isprabioacas/annals-frost-ingestor:latest
```

| Variable | Purpose |
|----------|---------|
| `FROST_BASE_URL` | Full SensorThings root (**required** for upload; must include `/FROST-Server/v1.1/`) |
| `ANNALS_MAX_OBSERVATIONS_PER_BATCH` | Observations per `$batch` (default: `1000`) |
| `ANNALS_UPLOAD_PARALLELISM` | Concurrent datastream upload threads (default: `16`) |
| `ANNALS_UPLOAD_STRATEGY` | `NONE`, `DELETE_BEFORE_UPLOAD`, `DETERMINISTIC_ID` (default: `DETERMINISTIC_ID`) |
| `ANNALS_FAST` | Stop after ~1000 observations (smoke test) |
| `ANNALS_DATA_REPO` / `ANNALS_DATA_REF` | Git source when `/data` is empty (default: this repo @ `main`) |
| `ANNALS_DATA_URL` | Optional direct archive URL (Zenodo / release tarball) instead of git |
| `ANNALS_SKIP_DOWNLOAD` | Fail if `/data` is empty instead of downloading (default: `false`) |
| `ANNALS_DOWNLOAD_FORCE` | Re-download raw data even if already present (default: `false`) |

Build the image only:

```bash
docker build -t annals-ingestor .
```

Run against a local FROST stack with already-mounted data (same as compose upload step):

```bash
docker run --rm \
  --entrypoint java \
  -e FROST_BASE_URL=http://frost-server:8080/FROST-Server/v1.1/ \
  -e ANNALS_DATA_FOLDER=/data \
  -e ANNALS_LOCAL_FOLDER=/data/processed \
  -e ANNALS_PREPARE=false \
  -e ANNALS_MAP=false \
  -e ANNALS_UPLOAD=true \
  -v /path/to/data:/data \
  annals-ingestor \
  -cp /app/ingestor-1.0-SNAPSHOT.jar:/app/dependency/* \
  eu.flora.essi.ingestor.annals.AnnalsIngestor
```

## Dependencies

- Apache Commons CSV
- org.json (JSON)
