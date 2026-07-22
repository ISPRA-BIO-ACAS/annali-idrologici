# Annals HydroServer ingestor
# Copyright (C) 2026 National Research Council of Italy (CNR)/Institute of Technologies and Environmental Intelligence (ITIAm)/ESSI-Lab
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

import os
import logging
import time
from datetime import datetime, timezone

import requests
from hydroserverpy import HydroServer
from annali_ispra.ingest import AnnaliIngestor

# Environment variables (defaults for local/docker):
# HYDROSERVER_URL (e.g. http://host.docker.internal:8000 or https://my-hs.example.com),
# HYDROSERVER_EMAIL, HYDROSERVER_PASSWORD,
# HS_DATA_DIR, HS_WORKSPACE_NAME, HS_FAST
def _format_duration(elapsed_ms: int) -> str:
    hours, rem = divmod(elapsed_ms, 3_600_000)
    minutes, rem = divmod(rem, 60_000)
    seconds, millis = divmod(rem, 1_000)
    return f"{hours}:{minutes:02d}:{seconds:02d}.{millis:03d}"


def _is_email_password_mismatch(e: requests.exceptions.HTTPError) -> bool:
    if e.response is None or not e.response.text:
        return False
    try:
        body = e.response.json()
        errors = body.get("errors") or []
        return any(
            err.get("code") == "email_password_mismatch"
            for err in errors
            if isinstance(err, dict)
        )
    except Exception:
        return False


def main():
    logging.basicConfig(level=logging.INFO)
    started_at = time.monotonic()
    started_wall = datetime.now(timezone.utc)
    logging.info("Ingestion started at %s", started_wall.isoformat())

    url = os.environ.get("HYDROSERVER_URL", "http://host.docker.internal:8000").strip().rstrip("/")
    email = os.environ.get("HYDROSERVER_EMAIL", "admin@localhost").strip()
    password = os.environ.get("HYDROSERVER_PASSWORD", "")

    if not url:
        raise SystemExit(
            "HYDROSERVER_URL is required (e.g. http://host.docker.internal:8000 "
            "or https://my-hydroserver.example.com)"
        )
    if not email:
        raise SystemExit("HYDROSERVER_EMAIL is required")
    if not password:
        raise SystemExit("HYDROSERVER_PASSWORD is required")

    logging.info("Email: %s", email)
    logging.info("URL: %s", url)

    max_attempts = int(os.environ.get("HS_AUTH_RETRIES", "100"))
    retry_delay_sec = int(os.environ.get("HS_AUTH_RETRY_DELAY", "60"))

    logging.info("Connecting to HydroServer at %s as %s", url, email)
    api = None
    last_error = None
    for attempt in range(1, max_attempts + 1):
        try:
            api = HydroServer(host=url, email=email, password=password)
            logging.info("Using HydroServer API at %s", url)
            break
        except requests.exceptions.HTTPError as e:
            last_error = e
            status = e.response.status_code if e.response is not None else None
            logging.warning(
                "HydroServer auth failed (attempt %d/%d): %s %s",
                attempt, max_attempts,
                status or "?",
                e.response.reason if e.response is not None else "",
            )
            if e.response is not None:
                logging.warning("Response body: %s", (e.response.text or "")[:500])
            # Retry on email/password mismatch or any 401 (e.g. admin not ready, session endpoint not ready)
            retryable = (
                attempt < max_attempts
                and (
                    _is_email_password_mismatch(e)
                    or status == 401
                )
            )
            if retryable:
                logging.info("Waiting %ds before retry (auth may not be ready yet)...", retry_delay_sec)
                time.sleep(retry_delay_sec)
            else:
                logging.error("Request URL: %s", e.response.url if e.response is not None else "?")
                raise
    if api is None:
        if last_error is not None:
            raise last_error
        raise RuntimeError("Failed to connect to HydroServer")

    data_dir = os.environ.get("HS_DATA_DIR", "/data")
    workspace_name = os.environ.get("HS_WORKSPACE_NAME", "Annali")
    fast = os.environ.get("HS_FAST", "true").lower() in ("true", "1", "yes")

    ingestor = AnnaliIngestor(api, data_dir=data_dir)
    ingestor.ingest(workspace_name=workspace_name, fast=fast)

    ended_wall = datetime.now(timezone.utc)
    elapsed_ms = int((time.monotonic() - started_at) * 1000)
    logging.info("Ingestion complete")
    logging.info("Annals ingestion ended at %s", ended_wall.isoformat())
    logging.info(
        "Annals ingestion duration: %s (HH:mm:ss.SSS)",
        _format_duration(elapsed_ms),
    )


if __name__ == "__main__":
    main()
