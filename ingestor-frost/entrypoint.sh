#!/bin/sh
# Annals FROST ingestor entrypoint.
#
# Hub / bring-your-own-FROST mode (default ENTRYPOINT):
#   - downloads Annals data from GitHub when /data is empty
#   - runs prepare + map + upload against FROST_BASE_URL
#
# Local compose files override this ENTRYPOINT and mount ./data, so they are unchanged.
set -eu

DATA_FOLDER="${ANNALS_DATA_FOLDER:-/data}"
LOCAL_FOLDER="${ANNALS_LOCAL_FOLDER:-${DATA_FOLDER}/processed}"
DATA_REPO="${ANNALS_DATA_REPO:-https://github.com/ISPRA-BIO-ACAS/annali-idrologici.git}"
DATA_REF="${ANNALS_DATA_REF:-main}"
DATA_URL="${ANNALS_DATA_URL:-}"
SKIP_DOWNLOAD="${ANNALS_SKIP_DOWNLOAD:-false}"
FORCE_DOWNLOAD="${ANNALS_DOWNLOAD_FORCE:-false}"

JAVA_CP="/app/ingestor-1.0-SNAPSHOT.jar:/app/dependency/*"
MAIN_CLASS="eu.flora.essi.ingestor.annals.AnnalsIngestor"

data_present() {
  [ -f "${DATA_FOLDER}/COMPARTIMENTO.csv" ] || [ -f "${DATA_FOLDER}/TIPO_GRANDEZZA.csv" ]
}

download_from_url() {
  url="$1"
  tmp="/tmp/annals-data-download"
  archive="/tmp/annals-data-archive"
  rm -rf "$tmp"
  mkdir -p "$tmp" "$DATA_FOLDER"

  echo "Downloading Annals data from ${url} ..."
  curl -fL --retry 3 --retry-delay 2 -o "$archive" "$url"

  case "$url" in
    *.zip|*/zip/*|*.ZIP)
      mkdir -p "${tmp}/extract"
      unzip -q "$archive" -d "${tmp}/extract"
      ;;
    *)
      mkdir -p "${tmp}/extract"
      # GitHub / Zenodo tarballs; ignore unknown compression and try tar
      if ! tar -xf "$archive" -C "${tmp}/extract" 2>/dev/null; then
        unzip -q "$archive" -d "${tmp}/extract"
      fi
      ;;
  esac

  if [ -d "${tmp}/extract/data" ]; then
    src="${tmp}/extract/data"
  else
    # archive may be repo-root-<sha>/data or a bare data tree
    found="$(find "${tmp}/extract" -maxdepth 3 -type d -name data 2>/dev/null | head -n 1 || true)"
    if [ -n "$found" ] && [ -f "${found}/COMPARTIMENTO.csv" ]; then
      src="$found"
    else
      src="${tmp}/extract"
    fi
  fi

  # Copy into place without wiping an existing processed/ cache if present
  echo "Installing raw data into ${DATA_FOLDER} ..."
  find "$src" -mindepth 1 -maxdepth 1 ! -name processed -exec cp -a {} "${DATA_FOLDER}/" \;

  rm -rf "$tmp" "$archive"
}

download_from_git() {
  tmp="/tmp/annals-git-sparse"
  rm -rf "$tmp"
  mkdir -p "$DATA_FOLDER"

  echo "Cloning Annals data (sparse: data/) from ${DATA_REPO} @ ${DATA_REF} ..."
  echo "This can take a while and needs several GB of disk space."
  git clone --depth 1 --filter=blob:none --sparse --branch "$DATA_REF" "$DATA_REPO" "$tmp"
  git -C "$tmp" sparse-checkout set data

  echo "Installing raw data into ${DATA_FOLDER} ..."
  find "$tmp/data" -mindepth 1 -maxdepth 1 ! -name processed -exec cp -a {} "${DATA_FOLDER}/" \;

  rm -rf "$tmp"
}

ensure_data() {
  if [ "$FORCE_DOWNLOAD" = "true" ]; then
    echo "ANNALS_DOWNLOAD_FORCE=true: re-downloading raw Annals data"
  elif data_present; then
    echo "Annals raw data found in ${DATA_FOLDER}"
    return 0
  fi

  if [ "$SKIP_DOWNLOAD" = "true" ]; then
    echo "ERROR: No Annals data in ${DATA_FOLDER} and ANNALS_SKIP_DOWNLOAD=true" >&2
    echo "Mount data at ${DATA_FOLDER} or unset ANNALS_SKIP_DOWNLOAD." >&2
    exit 1
  fi

  mkdir -p "$DATA_FOLDER"

  if [ -n "$DATA_URL" ]; then
    download_from_url "$DATA_URL"
  else
    download_from_git
  fi

  if ! data_present; then
    echo "ERROR: Download finished but ${DATA_FOLDER}/COMPARTIMENTO.csv (or TIPO_GRANDEZZA.csv) is missing." >&2
    exit 1
  fi
  echo "Annals raw data ready in ${DATA_FOLDER}"
}

apply_hub_defaults() {
  # Compose files set these explicitly; Hub users get a one-shot full pipeline.
  : "${ANNALS_PREPARE:=true}"
  : "${ANNALS_MAP:=true}"
  : "${ANNALS_UPLOAD:=true}"
  : "${ANNALS_MAX_OBSERVATIONS_PER_BATCH:=1000}"
  : "${ANNALS_UPLOAD_PARALLELISM:=16}"
  : "${ANNALS_UPLOAD_STRATEGY:=DETERMINISTIC_ID}"
  export ANNALS_PREPARE ANNALS_MAP ANNALS_UPLOAD
  export ANNALS_MAX_OBSERVATIONS_PER_BATCH ANNALS_UPLOAD_PARALLELISM ANNALS_UPLOAD_STRATEGY
  export ANNALS_DATA_FOLDER="$DATA_FOLDER"
  export ANNALS_LOCAL_FOLDER="$LOCAL_FOLDER"
}

ensure_data
apply_hub_defaults

if [ -z "${FROST_BASE_URL:-}" ] && [ "${ANNALS_UPLOAD}" = "true" ]; then
  echo "ERROR: FROST_BASE_URL is required when ANNALS_UPLOAD=true." >&2
  echo "Example: -e FROST_BASE_URL=https://my-frost.example.com/FROST-Server/v1.1/" >&2
  exit 1
fi

echo "Starting Annals FROST ingestor"
echo "  FROST_BASE_URL=${FROST_BASE_URL:-<not set>}"
echo "  ANNALS_DATA_FOLDER=${ANNALS_DATA_FOLDER}"
echo "  ANNALS_LOCAL_FOLDER=${ANNALS_LOCAL_FOLDER}"
echo "  ANNALS_PREPARE=${ANNALS_PREPARE} ANNALS_MAP=${ANNALS_MAP} ANNALS_UPLOAD=${ANNALS_UPLOAD}"
echo "  ANNALS_MAX_OBSERVATIONS_PER_BATCH=${ANNALS_MAX_OBSERVATIONS_PER_BATCH:-1000}"
echo "  ANNALS_UPLOAD_PARALLELISM=${ANNALS_UPLOAD_PARALLELISM:-16}"

if [ "$#" -gt 0 ]; then
  exec "$@"
fi

exec java -cp "$JAVA_CP" "$MAIN_CLASS"
