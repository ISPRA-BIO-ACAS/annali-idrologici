# Italian Hydrological Annals Interoperable Dataset

## Overview

This repository contains the digitized and quality-controlled database of the historical **Italian Hydrological Annals** (*Annali Idrologici Italiani*), together with the Docker-based infrastructure required to publish the data through **HydroServer** and **FROST Server**.

The database was produced by digitizing the original printed volumes of the *Annali Idrologici Italiani* and performing quality control procedures prior to publication.

The repository enables publication of the database through HydroServer, providing standardized and interoperable access to historical hydrological observations.

## Quickstart

The repository has the Annals data under `data/`. Prepared outputs are written to `data/processed/`; mapped STA JSON for FROST is written to `data/processed/sta/`. These folders are not committed to Git.

### Data preparation (shared by both HydroServer and FROST server)

Both publication targets use the same prepared CSV files under `data/processed/` (ZIP extraction and sorted `OSSERVAZIONI` files). FROST additionally needs the mapped STA folder at `data/processed/sta/`. HydroServer reads the prepared CSVs directly and does not use the STA folder. Run the following command to prepare the data (it will unzip, sort and map to STA).

```bash
docker compose -f docker-compose-annals-prepare.yml up --build
```

Then you are ready to publish with Docker:

### FROST Server

```bash
# 1. Start FROST Server and PostGIS (keep running) - note: this is a demo configuration, refer to FROST server documentation for production deployment.
docker compose -f docker-compose-frost.yml up -d

# 2. Upload mapped STA data to FROST (run prepare step first if needed)
docker compose -f docker-compose-annals-frost-ingestor.yml up --build
```

The FROST ingestor uploads `data/processed/sta/` to FROST on port **8082**. The FROST API is available at `http://localhost:8082/FROST-Server/v1.1/`.

To reset the database and start fresh:

```bash
docker compose -f docker-compose-frost.yml down -v
docker compose -f docker-compose-frost.yml up -d
```

### HydroServer

```bash
# 1. Start HydroServer and PostgreSQL (keep running)  - note: this is a demo configuration, refer to HydroServer documentation for production deployment.
docker compose -f docker-compose-hydroserver.yml up -d

# 2. Create the admin user with credentials matching docker-compose-annals-hs-ingestor.yml
#    by visiting http://localhost:8000/

# 3. Upload prepared CSV data into HydroServer (run prepare step first if needed)
docker compose -f docker-compose-annals-hs-ingestor.yml up --build
```

HydroServer is available at `http://localhost:8000/`.

To reset the database and start fresh:

```bash
docker compose -f docker-compose-hydroserver.yml down -v
docker compose -f docker-compose-hydroserver.yml up -d
```

## Repository Contents

The repository includes:

- the quality-controlled database dump of the digitized *Annali Idrologici Italiani*;
- Docker configuration for deploying HydroServer and FROST Server;
- configuration files and documentation required to publish the dataset.

## Citation

Each GitHub release is automatically archived by **Zenodo** and assigned a persistent DOI.

When citing this work:

- cite the **version DOI** corresponding to the specific release used in your work;
- cite the **concept DOI** when referring to the dataset as a whole.

## License

The contents of this repository are distributed under the **Creative Commons Attribution 4.0 International (CC BY 4.0)** License.

## Acknowledgements

The digitization, quality control, and publication of the *Annali Idrologici Italiani* have been carried out by **ISPRA BIO-ACAS**, with the contribution of the repository contributors listed in the citation metadata.
