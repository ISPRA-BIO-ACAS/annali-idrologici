# Ingestor

Maven project for ingesting Annals (ISPRA) hydrological data into a FROST Server (OGC SensorThings API). It provides a shared **FROSTClient** library, STA folder mapping/upload utilities, and the Annals ingestor.

## Structure

- **`eu.flora.essi.frost`** – Shared FROST client and SensorThings entity wrappers:
  - `FROSTClient` – CRUD and batch operations for Things, Datastreams, Observations, Sensors, ObservedProperties, Locations, FeaturesOfInterest
  - Entity classes: `Thing`, `Datastream`, `Observation`, `Sensor`, `ObservedProperty`, `Location`, `FeatureOfInterest`
  - `FilterBuilder`, `PagedResult`
  - Example classes: `FROSTClientAdvancedExample`, `FROSTClientFilterExample`

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

## Run Annals ingestor

Main class: **`eu.flora.essi.ingestor.annals.AnnalsIngestor`**

Set **`FROST_BASE_URL`** and point **`ANNALS_DATA_FOLDER`** at the raw `data/` directory:

```bash
mvn exec:java -Dexec.mainClass="eu.flora.essi.ingestor.annals.AnnalsIngestor"
```

Environment variables:

| Variable | Purpose |
|----------|---------|
| `FROST_BASE_URL` | FROST server base URL (required) |
| `ANNALS_DATA_FOLDER` | Raw data folder (default: `data`) |
| `ANNALS_LOCAL_FOLDER` | Working folder for processed data and STA output (default: `<ANNALS_DATA_FOLDER>/processed`) |
| `ANNALS_PREPARE` | Extract ZIPs and sort `OSSERVAZIONI` CSVs into `processed/` (default: `false`; use `AnnalsPrepareAndMap` or `docker-compose-annals-prepare.yml`) |
| `ANNALS_PREPARE_FORCE` | Re-extract and re-sort even if outputs already exist (default: `false`) |
| `ANNALS_MAP` | Map CSV → STA folder (default: `false`; use `AnnalsPrepareAndMap`) |
| `ANNALS_UPLOAD` | Upload STA data to FROST (default: `true`) |
| `ANNALS_FAST`, `ANNALS_MAX_OBSERVATIONS_PER_BATCH`, `ANNALS_UPLOAD_STRATEGY`, `ANNALS_USE_GZIP` | Upload tuning options |
| `ANNALS_UPLOAD_PARALLELISM` | Parallel datastream observation uploads (default: `8`) |
| `ANNALS_UPLOAD_VERBOSE` | Detailed per-datastream/batch upload logs (default: `false`) |

## Docker

Multi-stage **`Dockerfile`**: Maven build, then JRE runtime with **`AnnalsIngestor`**.

The repository root provides Docker Compose files:

- **`docker-compose-annals-prepare.yml`** – extract ZIPs, sort CSVs, map to STA (`./data` → `data/processed/` and `data/processed/sta/`)
- **`docker-compose-frost.yml`** – PostGIS + FROST Server (long-running)
- **`docker-compose-annals-frost-ingestor.yml`** – upload mapped STA data to FROST
- **`docker-compose-hydroserver.yml`** – HydroServer + PostgreSQL (long-running)
- **`docker-compose-annals-hs-ingestor.yml`** – upload prepared CSV data to HydroServer

See the root [README.md](../README.md).

Build the image only:

```bash
docker build -t annals-ingestor .
```

Run the ingestor manually:

```bash
docker run --rm \
  -e FROST_BASE_URL=http://frost-server:8080 \
  -e ANNALS_DATA_FOLDER=/data \
  -e ANNALS_LOCAL_FOLDER=/data/processed \
  -v /path/to/data:/data \
  annals-ingestor
```

## Dependencies

- Apache Commons CSV
- org.json (JSON)
