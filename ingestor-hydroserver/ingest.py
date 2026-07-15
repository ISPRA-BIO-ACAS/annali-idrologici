from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from tracemalloc import start
from typing import Any, Dict, Optional, Tuple, Iterable, List

import pandas as pd
import logging
from datetime import datetime
import pytz
import re

from .skos_client import SkosClient

# Work around hydroserverpy Thing model requiring fileAttachments when API response omits it
try:
    from hydroserverpy.api.models.sta import thing as _thing_mod
    from pydantic import Field

    _Thing = _thing_mod.Thing
    if "fileAttachments" in _Thing.model_fields:
        _Thing.model_fields["fileAttachments"] = Field(default_factory=list)
        _Thing.model_rebuild()
    # Also patch __init__ so list() responses (which bypass model_rebuild in some code paths) get default
    _orig_thing_init = _Thing.__init__

    def _thing_init_with_file_attachments(self, *args, **kwargs):
        if kwargs and "fileAttachments" not in kwargs:
            kwargs["fileAttachments"] = []
        return _orig_thing_init(self, *args, **kwargs)

    _Thing.__init__ = _thing_init_with_file_attachments
except Exception:
    pass

# Same for Datastream model (tags and fileAttachments required by pydantic but often omitted in API response)
try:
    from hydroserverpy.api.models.sta import datastream as _ds_mod
    from pydantic import Field

    _DS = _ds_mod.Datastream
    for field_name in ("tags", "fileAttachments"):
        if field_name in _DS.model_fields:
            _DS.model_fields[field_name] = Field(default_factory=list)
    _DS.model_rebuild()
    _orig_ds_init = _DS.__init__

    def _ds_init_with_defaults(self, *args, **kwargs):
        for key in ("tags", "fileAttachments"):
            if kwargs and key not in kwargs:
                kwargs[key] = []
        return _orig_ds_init(self, *args, **kwargs)

    _DS.__init__ = _ds_init_with_defaults
except Exception:
    pass

AGGREGATION_PERIOD_PATTERN = re.compile(
    r"^P"
    r"(?:(?P<years>\d+)Y)?"
    r"(?:(?P<months>\d+)M)?"
    r"(?:(?P<days>\d+)D)?"
    r"(?:T"
    r"(?:(?P<hours>\d+)H)?"
    r"(?:(?P<minutes>\d+)M)?"
    r"(?:(?P<seconds>\d+)S)?"
    r")?$"
)


@dataclass(frozen=True)

class SiteKey:
    compartimento: str
    station_alias: str
    basin_alias: str


class AnnaliIngestor:
    def __init__(self, api, data_dir: str | Path):
        """Initialize ingestor with HydroServer API.
        
        Args:
            api: A HydroServer API instance
            data_dir: Path to the data directory
        """
        self.api = api
        self.data_dir = Path(data_dir)
        self.encoding = "cp1252"
        self.sep = ","
        self.quotechar = '"'

        # Caches to avoid duplicates
        self._workspace_uid: Optional[str] = None
        self._observed_properties: Dict[str, str] = {}
        self._units: Dict[str, str] = {}
        self._sensors: Dict[str, str] = {}
        self._sites: Dict[SiteKey, str] = {}
        self._processing_level_uid: Optional[str] = None
        # Map keys: (tipo_grandezza, flag) -> result_qualifier_uid
        self._result_qualifiers: Dict[Tuple[str, str], str] = {}
        # Map GRANDEZZA code -> TIPO_GRANDEZZA
        self._grandezza_to_tipo: Dict[str, str] = {}
        # Map (COMPARTIMENTO, ALIAS_STAZIONE, ALIAS_BACINO, GRANDEZZA) -> metadata
        self._series_temporali_metadata: Dict[Tuple[str, str, str, str], Dict[str, Any]] = {}
        self._grandezza_sample_medium: Dict[str, str] = {}
        self._skos_client = SkosClient()

    # -------------------- Public API --------------------
    def ensure_workspace(self, name: str) -> str:
        for ws in self.api.workspaces.list().items:
            if ws.name == name:
                self._workspace_uid = str(ws.uid)
                return self._workspace_uid
        ws = self.api.workspaces.create(name=name, is_private=False)
        self._workspace_uid = str(ws.uid)
        return self._workspace_uid

    def ingest(self, workspace_name: str, datastream_status: str = "Ongoing", fast: bool = False) -> None:
        workspace_uid = self.ensure_workspace(workspace_name)
        coords = self._load_coordinates()
        strata = self._load_station_anagraphics(fast=fast)
        tipo_grandezza = self._load_tipo_grandezza()
        strumenti = self._load_strumenti()
        compartimenti = self._load_compartimenti()
        udm_map = self._load_udm()
        flag_quality = self._load_flag_quality()
        ruoli = self._load_ruoli()
        metadato_dati = self._load_metadato_dati()
        self._series_temporali_metadata = self._load_series_temporali()

        self.md = metadato_dati.iloc[0]  # Single row in v2

        # Build GRANDEZZA -> TIPO_GRANDEZZA map
        if not tipo_grandezza.empty:
            for _, r in tipo_grandezza.iterrows():
                gcode = str(r.get("GRANDEZZA", ""))
                tgr = str(r.get("TIPO_GRANDEZZA", ""))
                if gcode:
                    self._grandezza_to_tipo[gcode] = tgr
                    sample_medium = r.get("SAMPLE_MEDIUM")
                    if pd.notna(sample_medium):
                        sample_medium_str = str(sample_medium).strip()
                        if sample_medium_str:
                            self._grandezza_sample_medium[gcode] = sample_medium_str

        self._ensure_processing_level(workspace_uid)
        # Prepare qualifiers from FLAG_QUALITY
        self._prepare_result_qualifiers(workspace_uid, flag_quality)

        # Stream observations grouped by site × grandezza × unit
        data_files = self._find_files(["OSSERVAZIONI_*.csv"]) 
        for data_path in data_files:
            chunk_size = 100000
            if fast:
                chunk_size = 100
            chunks = pd.read_csv(
                data_path,
                sep=self.sep,
                quotechar=self.quotechar,
                encoding=self.encoding,
                chunksize=chunk_size,
                dtype={
                    "COMPARTIMENTO": "category",
                    "ALIAS_STAZIONE": "string",
                    "ALIAS_BACINO": "string",
                    "ANNO": "int32",
                    "MESE": "int16",
                    "GIORNO": "int16",
                    "ORA": "string",
                    "GRANDEZZA": "string",
                    "FLAG_VALORE": "string",
                },
                converters={
                    "VALORE": lambda s: self._parse_decimal_comma(s),
                },
            )

            for idx, df in enumerate(chunks, start=1):
                start_row = (idx - 1) * chunk_size
                end_row = (idx * chunk_size) - 1
                logging.info("[%s] Processing data file %s: chunk %d rows %d-%d", datetime.now().isoformat(), data_path.name, idx, start_row, end_row)
                # Build timestamp column with ORA (time) information in Europe/Rome timezone
                df["phenomenon_time"] = pd.to_datetime(
                    dict(year=df["ANNO"], month=df["MESE"], day=df["GIORNO"]),
                    errors="coerce",
                )
                # Add time component if ORA column exists
                if "ORA" in df.columns:
                    # Parse time from ORA column (format like "09:00")
                    df["time_component"] = pd.to_datetime(df["ORA"], format="%H:%M", errors="coerce").dt.time
                    # Combine date and time
                    df["phenomenon_time"] = df["phenomenon_time"] + pd.to_timedelta(df["time_component"].astype(str), errors="coerce")
                    df = df.drop(columns=["time_component"])
                
                # Set timezone to Europe/Rome
                rome_tz = pytz.timezone('Europe/Rome')
                df["phenomenon_time"] = df["phenomenon_time"].dt.tz_localize(rome_tz, ambiguous='infer')
                df = df.dropna(subset=["phenomenon_time"])  # skip bad dates

                # Group per stream key
                group_cols = ["COMPARTIMENTO", "ALIAS_STAZIONE", "ALIAS_BACINO", "GRANDEZZA"]
                for (comp, st_alias, basin_alias, grandezza), g in df.groupby(group_cols, observed=False):
                    comp = str(comp).strip()
                    st_alias = str(st_alias).strip()
                    basin_alias = str(basin_alias).strip()
                    site_uid = self._ensure_site(
                        workspace_uid,
                        SiteKey(str(comp), str(st_alias), str(basin_alias)),
                        coords,
                        strata,
                        compartimenti,
                        ruoli,
                        metadato_dati,
                        str(grandezza),
                        strumenti,
                    )
                    # Calculate UDM from TIPO_STRUMENTO_ANNALE
                    udm = None
                    strumento_type = tipo_grandezza.loc[tipo_grandezza["GRANDEZZA"] == grandezza].iloc[0]["SIGLA_STRUMENTO"]
                    udm_match = udm_map.loc[udm_map["SIGLA_STRUMENTO"] == strumento_type]
                    if not udm_match.empty and "UDM" in udm_match.columns:
                            udm = str(udm_match.iloc[0]["UDM"]) 

                    if udm is None:
                        logging.warning("Could not determine UDM for station %s, grandezza %s", st_alias, grandezza)
                        continue

                    sensor_uid = self._ensure_sensor(workspace_uid, strumento_type, strumenti)
                    observed_property_uid = self._ensure_observed_property(workspace_uid, grandezza, tipo_grandezza)
                    unit_uid = self._ensure_unit(workspace_uid, udm, udm_map)

                    grandezza_description = tipo_grandezza.loc[tipo_grandezza["GRANDEZZA"] == grandezza].iloc[0]["DESCRIZIONE_GRANDEZZA"]

                    datastream = self._ensure_datastream(
                        workspace_uid=workspace_uid,
                        site_uid=site_uid,
                        sensor_uid=sensor_uid,
                        observed_property_uid=observed_property_uid,
                        processing_level_uid=self._processing_level_uid,
                        unit_uid=unit_uid,
                        grandezza=grandezza,
                        grandezza_description=grandezza_description,
                        tipo_grandezza=tipo_grandezza,
                        udm=udm,
                        status=datastream_status,
                        compartimento=comp,
                        station_alias=st_alias,
                        basin_alias=basin_alias,
                    )

                    # Prepare observations frame, include flag for qualifier mapping
                    obs_df = g[["phenomenon_time", "VALORE", "FLAG_VALORE"]].rename(columns={"VALORE": "result", "FLAG_VALORE": "result_qualifier_codes"}).copy()
                    obs_df = obs_df.dropna(subset=["result"])  # keep only numeric results

                    def _map_qualifier(flag_value: Any) -> Optional[str]:
                        if pd.isna(flag_value):
                            return None
                        flag_str = str(flag_value).strip()
                        if not flag_str:
                            return None

                        return [flag_str]

                    obs_df["result_qualifier_codes"] = obs_df["result_qualifier_codes"].map(_map_qualifier)

                    # Detect and skip duplicates on phenomenon_time within this group
                    if not obs_df.empty:
                        dup_mask = obs_df.duplicated(subset=["phenomenon_time"], keep="first")
                        num_dups = int(dup_mask.sum())
                        if num_dups > 0:
                            # Log a concise warning with a small sample of duplicated timestamps
                            dup_times = obs_df.loc[dup_mask, "phenomenon_time"].astype(str).head(5).tolist()
                            logging.warning(
                                "Skipping %d duplicate observations (by phenomenon_time) for site=%s, grandezza=%s, udm=%s. Sample duplicates: %s",
                                num_dups,
                                st_alias,
                                grandezza,
                                udm,
                                ", ".join(dup_times),
                            )
                            obs_df = obs_df.loc[~dup_mask]

                    if not obs_df.empty:
                        if fast:
                            obs_df = obs_df.head(10)
                        datastream.load_observations(obs_df)
                if fast:
                    break; # only first chunk if fast

    # -------------------- Load lookups --------------------
    def _load_coordinates(self) -> Dict[SiteKey, Tuple[float, float, Optional[float]]]:
        files = self._find_files(["STAZIONI_*.csv"])
        coords: Dict[SiteKey, Tuple[float, float, Optional[float]]] = {}
        for path in files:
            df = pd.read_csv(
                path,
                sep=self.sep,
                quotechar=self.quotechar,
                encoding=self.encoding,
                dtype={"Compartimento": "category", "ALIAS_STAZIONE": "string", "ALIAS_BACINO": "string"},
                converters={"X_LONG": self._parse_decimal_comma, "Y_LAT": self._parse_decimal_comma, "Z_MSLM": self._parse_decimal_comma},
            )
            for _, r in df.iterrows():
                key = SiteKey(str(r["Compartimento"]), str(r["ALIAS_STAZIONE"]), str(r["ALIAS_BACINO"]))
                elevation = float(r["Z_MSLM"]) if pd.notna(r["Z_MSLM"]) else None
                coords[key] = (float(r["Y_LAT"]), float(r["X_LONG"]), elevation)  # lat, lon, elevation
        return coords

    def _load_station_anagraphics(self, fast: bool = False) -> Dict[SiteKey, pd.Series]:
        # Load station anagraphics from observations file (v2 consolidated structure)
        files = self._find_files(["OSSERVAZIONI_*.csv"])
        strata: Dict[SiteKey, pd.Series] = {}

        for path in files:
            # Read all data in chunks to get complete station metadata
            chunk_size = 100000
            if fast:
                chunk_size = 100
            chunks = pd.read_csv(
                path,
                sep=self.sep,
                quotechar=self.quotechar,
                encoding=self.encoding,
                chunksize=chunk_size,
                dtype={
                    "COMPARTIMENTO": "category",
                    "ALIAS_STAZIONE": "string",
                    "ALIAS_BACINO": "string",
                    "TABELLA_ORDINE": "string",
                    "PAGINA": "string",
                    "PARTE_ANNALE": "string",
                    "SIGLA_ENTE_COMPILATORE": "string",
                    "NOME_COMPILATORE": "string",
                    "TIPO_STRUMENTO_ANNALE": "string",
                },
            )
            
            for idx, df in enumerate(chunks, start=1):
                logging.info("Processing station metadata from %s: chunk %d", path.name, idx)
                for _, r in df.iterrows():
                    key = SiteKey(str(r["COMPARTIMENTO"]), str(r["ALIAS_STAZIONE"]), str(r["ALIAS_BACINO"]))
                    if key not in strata:
                        # Initialize metadata with sets for multi-value fields
                        metadata_row = {
                            "Tabella_Ordine": set(),
                            "Pagina": set(),
                            "Parte_Annale": set(),
                            "Editor_Organization": set(),
                            "Sensori": set(),
                        }
                        strata[key] = pd.Series(metadata_row)    
                    
                    strata[key]["Editor_Organization"].add((str(r["SIGLA_ENTE_COMPILATORE"]), None))

                    if pd.notna(r.get("TABELLA_ORDINE", None)):
                        strata[key]["Tabella_Ordine"].add(str(r["TABELLA_ORDINE"]))
                    if pd.notna(r.get("PAGINA", None)):
                        strata[key]["Pagina"].add(str(r["PAGINA"]))
                    if pd.notna(r.get("PARTE_ANNALE", None)):
                        strata[key]["Parte_Annale"].add(str(r["PARTE_ANNALE"]))
                    if pd.notna(r.get("SIGLA_ENTE_COMPILATORE", None)) and pd.notna(r.get("NOME_COMPILATORE", None)):
                        strata[key]["Editor_Organization"].add((str(r["SIGLA_ENTE_COMPILATORE"]), str(r["NOME_COMPILATORE"])))
                    else:
                        if pd.notna(r.get("SIGLA_ENTE_COMPILATORE", None)):
                            strata[key]["Editor_Organization"].add((str(r["SIGLA_ENTE_COMPILATORE"], None)))
                        if pd.notna(r.get("NOME_COMPILATORE", None)):
                            strata[key]["Editor_Organization"].add((None, str(r["NOME_COMPILATORE"])))
                    if pd.notna(r.get("TIPO_STRUMENTO_ANNALE", None)):
                        strata[key]["Sensori"].add(str(r["TIPO_STRUMENTO_ANNALE"]))
                if (fast and idx>0): # only first chunk if fast
                    break; # only first chunk if fast
        return strata

    def _load_tipo_grandezza(self) -> pd.DataFrame:
        path = self.data_dir / "TIPO_GRANDEZZA.csv"
        return pd.read_csv(path, sep=',', quotechar=self.quotechar, encoding=self.encoding)

    def _load_strumenti(self) -> pd.DataFrame:
        path = self.data_dir / "TIPO_STRUMENTO.csv"
        return pd.read_csv(path, sep=self.sep, quotechar=self.quotechar, encoding=self.encoding)

    def _load_compartimenti(self) -> pd.DataFrame:
        path = self.data_dir / "COMPARTIMENTO.csv"
        return pd.read_csv(path, sep=self.sep, quotechar=self.quotechar, encoding=self.encoding)

    def _load_udm(self) -> pd.DataFrame:
        path = self.data_dir / "UNITA_MISURA_UDM.csv"
        return pd.read_csv(path, sep=self.sep, quotechar=self.quotechar, encoding=self.encoding)

    def _load_flag_quality(self) -> pd.DataFrame:
        path = self.data_dir / "FLAG_QUALITY.csv"
        try:
            return pd.read_csv(path, sep=self.sep, quotechar=self.quotechar, encoding=self.encoding)
        except FileNotFoundError:
            return pd.DataFrame()

    def _load_ruoli(self) -> pd.DataFrame:
        path = self.data_dir / "ENTE_COMPILATORE_rev.csv"
        try:
            return pd.read_csv(path, sep=self.sep, quotechar=self.quotechar, encoding=self.encoding)
        except FileNotFoundError:
            return pd.DataFrame()

    def _load_metadato_dati(self) -> pd.DataFrame:
        path = self.data_dir / "GENERAL_METADATA.csv"
        try:
            return pd.read_csv(path, sep=self.sep, quotechar=self.quotechar, encoding=self.encoding)
        except FileNotFoundError:
            return pd.DataFrame()

    def _load_series_temporali(self) -> Dict[Tuple[str, str, str, str], Dict[str, Any]]:
        metadata: Dict[Tuple[str, str, str, str], Dict[str, Any]] = {}
        files = self._find_files(["SERIE_TEMPORALI*.csv"])
        if not files:
            return metadata

        grandezza_defaults = self._load_grandezza_defaults()

        for path in files:
            try:
                df = pd.read_csv(
                    path,
                    sep=None,
                    engine="python",
                    quotechar=self.quotechar,
                    encoding=self.encoding,
                )
            except Exception as exc:
                logging.warning("Unable to read series metadata file %s: %s", path, exc)
                continue

            required_cols = {"COMPARTIMENTO", "ALIAS_STAZIONE", "ALIAS_BACINO", "GRANDEZZA"}
            missing_required = required_cols - set(df.columns)
            if missing_required:
                logging.warning(
                    "Skipping %s: missing required columns %s",
                    path,
                    ", ".join(sorted(missing_required)),
                )
                continue

            for _, row in df.iterrows():
                key = self._build_series_key(
                    row.get("COMPARTIMENTO"),
                    row.get("ALIAS_STAZIONE"),
                    row.get("ALIAS_BACINO"),
                    row.get("GRANDEZZA"),
                )
                if not key[0] or not key[1] or not key[3]:
                    continue

                entry = metadata.setdefault(key, {})

                stat = self._coerce_optional_string(row.get("INTERPOLATION_TYPE"))
                if stat:
                    entry["aggregation_statistic"] = stat

                interval_value, interval_unit = self._parse_aggregation_period(row.get("AGGREGATION_PERIOD"))
                if interval_value is not None:
                    entry["time_aggregation_interval"] = interval_value
                    if "intended_time_spacing" not in entry:
                        entry["intended_time_spacing"] = interval_value
                if interval_unit:
                    entry["time_aggregation_interval_unit"] = interval_unit
                    if "intended_time_spacing_unit" not in entry:
                        entry["intended_time_spacing_unit"] = interval_unit

                intended_value = row.get("INTENDED_TIME_SPACING")
                if pd.notna(intended_value):
                    try:
                        entry["intended_time_spacing"] = int(float(intended_value))
                    except (TypeError, ValueError):
                        pass
                intended_unit = self._coerce_optional_string(row.get("INTENDED_TIME_SPACING_UNIT"))
                if intended_unit:
                    entry["intended_time_spacing_unit"] = intended_unit

                grandezza_fallback = grandezza_defaults.get(key[3])
                self._apply_missing_defaults(entry, grandezza_fallback)

        if metadata and grandezza_defaults:
            for key, entry in metadata.items():
                if key[3] in grandezza_defaults:
                    self._apply_missing_defaults(entry, grandezza_defaults[key[3]])

        return metadata

    def _load_grandezza_defaults(self) -> Dict[str, Dict[str, Any]]:
        defaults: Dict[str, Dict[str, Any]] = {}
        path = self.data_dir / "TIPO_GRANDEZZA.csv"
        if not path.exists():
            return defaults

        try:
            df = pd.read_csv(
                path,
                sep=None,
                engine="python",
                quotechar=self.quotechar,
                encoding=self.encoding,
            )
        except Exception as exc:
            logging.warning("Unable to read grandezza defaults from %s: %s", path, exc)
            return defaults

        for _, row in df.iterrows():
            grandezza = self._normalize_key_component(row.get("GRANDEZZA"))
            if not grandezza:
                continue
            entry = defaults.setdefault(grandezza, {})
            stat = self._coerce_optional_string(row.get("INTERPOLATION_TYPE"))
            if stat:
                entry["aggregation_statistic"] = stat
            interval_value, interval_unit = self._parse_aggregation_period(row.get("AGGREGATION_PERIOD"))
            if interval_value is not None:
                entry.setdefault("time_aggregation_interval", interval_value)
                entry.setdefault("intended_time_spacing", interval_value)
            if interval_unit:
                entry.setdefault("time_aggregation_interval_unit", interval_unit)
                entry.setdefault("intended_time_spacing_unit", interval_unit)
            sample_medium = self._coerce_optional_string(row.get("SAMPLE_MEDIUM"))
            if sample_medium:
                entry.setdefault("sampled_medium", sample_medium)
        return defaults

    # -------------------- Ensure entities --------------------
    def _ensure_processing_level(self, workspace_uid: str) -> str:
        if self._processing_level_uid:
            return self._processing_level_uid
        # try find existing 'Raw'
        for pl in self.api.processinglevels.list(workspace=workspace_uid).items:
            if getattr(pl, "definition", "").lower() == "raw" or getattr(pl, "code", None) == "0":
                self._processing_level_uid = str(pl.uid)
                return self._processing_level_uid
        lod = str(self.md["LevelOfData"])[:255]
        pl = self.api.processinglevels.create(
            code='0',
            definition="Quality controlled data",
            explanation=lod,
            workspace=workspace_uid
        )
        self._processing_level_uid = str(pl.uid)
        return self._processing_level_uid

    def _ensure_site(self, workspace_uid: str, key: SiteKey, coords: Dict[SiteKey, Tuple[float, float, Optional[float]]], strata: Dict[SiteKey, pd.Series], compartimenti: pd.DataFrame, ruoli: pd.DataFrame, metadato_dati: pd.DataFrame, grandezza: str, strumenti: pd.DataFrame) -> str:
        if key in self._sites:
            return self._sites[key]

        lat, lon, elevation = None, None, None
        if key in coords:
            lat, lon, elevation = coords[key]

        name = key.station_alias

        # try list and find by code
        for thing in self.api.things.list(workspace=workspace_uid).items:
            if thing.sampling_feature_code == f"{key.compartimento}-{key.basin_alias}-{key.station_alias}":
                self._sites[key] = str(thing.uid)
                return self._sites[key]

        elevation_value = None
        disclaimer = "WARNING: These data may be provisional and subject to revision."
        amd = metadato_dati.iloc[0]  # Single row in v2
        if "Disclaimer" in metadato_dati.columns:
            disclaimer = str(amd["Disclaimer"])[:255]
        if elevation is not None:
            elevation_value = elevation
        elif key in strata and hasattr(strata[key], "Quota"):
            elevation_value = self._parse_decimal_comma(strata[key].Quota)

        compartimento = None

        row_match = compartimenti.loc[compartimenti["COMPARTIMENTO"] == key.compartimento]
        if not row_match.empty and "NOME_COMPARTIMENTO" in row_match.columns:
            compartimento = str(row_match.iloc[0]["NOME_COMPARTIMENTO"])

        thing_kwargs = {
            "workspace": workspace_uid,
            "name": name,
            "description": f"Compartimento: {compartimento}, bacino: {key.basin_alias}, stazione: {key.station_alias}",
            "sampling_feature_type": "Site",
            "sampling_feature_code": f"{key.compartimento}-{key.basin_alias}-{key.station_alias}",
            "site_type": "Hydrology",
            "latitude": lat,
            "longitude": lon,
            "elevation_m": elevation_value,
            "country": "IT",
            "admin_area_2": key.basin_alias,
            "admin_area_1": compartimento,
            "data_disclaimer": disclaimer,
            "is_private": False,
        }
        logging.info(
            "Creating site (Thing): name=%s workspace=%s",
            name, workspace_uid,
        )
        logging.debug("things.create request keyword names: %s", list(thing_kwargs.keys()))
        try:
            new_site = self.api.things.create(**thing_kwargs)
        except Exception:
            logging.error(
                "things.create failed. Request keyword names sent: %s",
                list(thing_kwargs.keys()),
            )
            raise
        # Add custom properties (tags) from station anagraphics if available
        try:

            if key in strata:
                row = strata[key]                     
                if hasattr(row, "Tabella_Ordine") and pd.notna(row.Tabella_Ordine):
                    # Handle set of values - concatenate with comma
                    if isinstance(row.Tabella_Ordine, set):
                        values = sorted([str(v) for v in row.Tabella_Ordine if pd.notna(v)])
                        if values:
                            tag = ",".join(values)[:255]
                            new_site.add_tag("annals_table", tag)
                    else:
                        new_site.add_tag("annals_table", str(row.Tabella_Ordine)[:255])
                if hasattr(row, "Pagina") and pd.notna(row.Pagina):
                    # Handle set of values - concatenate with comma
                    if isinstance(row.Pagina, set):
                        values = sorted([str(v) for v in row.Pagina if pd.notna(v)])
                        if values:
                            new_site.add_tag("annals_page", ",".join(values)[:255])
                    else:
                        new_site.add_tag("annals_page", str(row.Pagina)[:255])
                if hasattr(row, "Parte_Annale") and pd.notna(row.Parte_Annale):
                    # Handle set of values - concatenate with comma
                    if isinstance(row.Parte_Annale, set):
                        values = sorted([str(v) for v in row.Parte_Annale if pd.notna(v)])
                        if values:
                            new_site.add_tag("annals_part", ",".join(values)[:255])
                    else:
                        new_site.add_tag("annals_part", str(row.Parte_Annale)[:255])
                if hasattr(row, "Editor_Organization") and pd.notna(row.Editor_Organization):
                    if isinstance(row.Editor_Organization, set):
                        enti_to_process = row.Editor_Organization
                    else:
                        enti_to_process = [row.Editor_Organization]

                    i = 0;

                    for ente in enti_to_process:
                        i+=1
                        sigla_ente, editor_name = ente
                        if sigla_ente is not None and ruoli is not None and not ruoli.empty and "SIGLA_ENTE_COMPILATORE" in ruoli.columns:
                            match = ruoli.loc[ruoli["SIGLA_ENTE_COMPILATORE"] == sigla_ente]
                            org_label = None
                            roles = []
                            email = None
                            if not match.empty:
                                m = match.iloc[0]
                                if "NOME_ENTE_COMPILATORE" in match.columns and pd.notna(m.get("NOME_ENTE_COMPILATORE", None)):
                                    org_label = str(m["NOME_ENTE_COMPILATORE"])
                                if "RUOLO_ENTE_COMPILATORE" in match.columns and pd.notna(m.get("RUOLO_ENTE_COMPILATORE", None)):
                                    roles = str(m["RUOLO_ENTE_COMPILATORE"]).split(",")
                                if "EmailpuntoContatto" in match.columns and pd.notna(m.get("EmailpuntoContatto", None)):
                                    email = str(m["EmailpuntoContatto"])

                            orgValue = "name: " + org_label
                            if email is not None:
                                orgValue += ", email: " + email 
                            if editor_name is not None:
                                orgValue += ", individual: " + editor_name
                            if roles:
                                for role in roles:
                                    orgValue += (", role: " + role)                                
                            new_site.add_tag(("organization_"+str(i)), orgValue[:255])
                            
                    
                new_site.add_tag("watershed", str(key.basin_alias)[:255])
                if compartimento is not None:
                    new_site.add_tag("district", compartimento[:255])
                sensor_types = []
                for sensor_code in row.Sensori:
                    strumenti.loc[strumenti["TIPO_STRUMENTO_ANNALE"] == sensor_code]
                    if not row_match.empty and "DESCRIZIONE_TIPO_STRUMENTO_ANNALE" in row_match.columns:
                        description = str(row_match.iloc[0]["DESCRIZIONE_TIPO_STRUMENTO_ANNALE"]) or sensor_code
                        sensor_types.append(description)
                
                if sensor_types:
                    new_site.add_tag("sensor_type", ",".join(sensor_types)[:255])
                # Add GENERAL_METADATA site-level tags (global metadata in v2)
                try:
                    if metadato_dati is not None and not metadato_dati.empty:                        
                        if "TerritoryName" in metadato_dati.columns and pd.notna(self.md.get("TerritoryName", None)):
                            new_site.add_tag("territory_of_origin", str(self.md["TerritoryName"])[:255])
                        if "DataSource" in metadato_dati.columns and pd.notna(self.md.get("DataSource", None)):
                            new_site.add_tag("data_source", str(self.md["DataSource"])[:255])
                        if "licence" in metadato_dati.columns and pd.notna(self.md.get("licence", None)):
                            new_site.add_tag("licence", str(self.md["licence"])[:255])
                        if "Funding" in metadato_dati.columns and pd.notna(self.md.get("Funding", None)):
                            new_site.add_tag("funding", str(self.md["Funding"])[:255])
                        if "Disclaimer" in metadato_dati.columns and pd.notna(self.md.get("Disclaimer", None)):
                            new_site.add_tag("disclaimer", str(self.md["Disclaimer"])[:255])
                except Exception:
                    logging.warning("Failed adding GENERAL_METADATA tags for site %s", name, exc_info=True)

        except Exception:
            # Non-fatal; continue even if tag addition fails
            logging.warning("Failed adding tags to site %s", name, exc_info=True)
        self._sites[key] = str(new_site.uid)
        return self._sites[key]

    def _ensure_sensor(self, workspace_uid: str, instrument_code: str, strumenti: pd.DataFrame) -> str:        

        cache_key = instrument_code
        if cache_key in self._sensors:
            return self._sensors[cache_key]

        # Try to find existing sensor by name (instrument code)
        for sensor in self.api.sensors.list(workspace=workspace_uid).items:
            if sensor.name == instrument_code:
                self._sensors[cache_key] = str(sensor.uid)
                return self._sensors[cache_key]

        # Lookup description from strumenti table (v2 schema)
        description = instrument_code
        row_match = strumenti.loc[strumenti["SIGLA_STRUMENTO"] == instrument_code]
        if not row_match.empty and "CATEGORIA_STRUMENTO" in row_match.columns:
            description = str(row_match.iloc[0]["CATEGORIA_STRUMENTO"]) or instrument_code

        new_sensor = self.api.sensors.create(
            name=description,
            description=description,
            encoding_type='application/json',
            manufacturer='N/A',
            sensor_model='N/A',
            sensor_model_link='',
            method_type='Sensor',
            method_link='',
            method_code=instrument_code,
            workspace=workspace_uid
        )
        self._sensors[cache_key] = str(new_sensor.uid)
        return self._sensors[cache_key]

    def _ensure_observed_property(self, workspace_uid: str, grandezza: str, tipo_grandezza: pd.DataFrame) -> str:
        if grandezza in self._observed_properties:
            return self._observed_properties[grandezza]
        # find description from v2 schema
        row = tipo_grandezza.loc[tipo_grandezza["GRANDEZZA"] == grandezza]
        description = None
        tipo = None
        if not row.empty:            
            tipo = str(row.iloc[0]["TIPO_GRANDEZZA"]) if "TIPO_GRANDEZZA" in row.columns else None
        # tipo is a WMO URI
        tipo_label = None
        if tipo and isinstance(tipo, str) and tipo.startswith("http"):
            tipo_label = self._skos_client.get_pref_label(tipo)
        # tipo_label is the label of the WMO URI
        for op in self.api.observedproperties.list(workspace=workspace_uid).items:
            if op.code == tipo:
                self._observed_properties[tipo] = str(op.uid)
                return self._observed_properties[tipo]
        new_op = self.api.observedproperties.create(
            name=tipo_label,
            definition=tipo_label,
            description=tipo_label or grandezza,
            observed_property_type='Hydrology',
            code=tipo,
            workspace=workspace_uid
        )
        self._observed_properties[grandezza] = str(new_op.uid)
        return self._observed_properties[grandezza]

    def _ensure_unit(self, workspace_uid: str, udm_code: str, udm_map: pd.DataFrame) -> str:
        if udm_code in self._units:
            return self._units[udm_code]
        row = udm_map.loc[udm_map["UDM"] == udm_code]
        name = symbol = definition = udm_code
        if not row.empty:
            name = str(row.iloc[0]["DESCRIZIONE_UDM"]) if "DESCRIZIONE_UDM" in row.columns else udm_code
            symbol = udm_code
            definition = name
        # try existing
        for unit in self.api.units.list(workspace=workspace_uid).items:
            if unit.symbol == symbol:
                self._units[udm_code] = str(unit.uid)
                return self._units[udm_code]
        new_unit = self.api.units.create(
            name=name,
            symbol=symbol,
            definition=definition,
            unit_type='Hydrology',
            workspace=workspace_uid
        )
        self._units[udm_code] = str(new_unit.uid)
        return self._units[udm_code]

    def _ensure_datastream(
        self,
        workspace_uid: str,
        site_uid: str,
        sensor_uid: str,
        observed_property_uid: str,
        processing_level_uid: str,
        unit_uid: str,
        grandezza: str,
        grandezza_description: str,    
        tipo_grandezza: pd.DataFrame,
        udm: str,
        status: str,
        compartimento: str,
        station_alias: str,
        basin_alias: str,
    ):
        name = station_alias + " - " + grandezza_description
        name = name[:199]
        # try existing for this site + property + unit
        existing = self.api.datastreams.list(
            workspace=workspace_uid,
            thing=site_uid,
            sensor=sensor_uid,
            observed_property=observed_property_uid,
            unit=unit_uid,
        )
        for ds in getattr(existing, "items", []):
            if ds.name == name:
                return ds
        defaults = self._get_series_temporali_defaults(
            compartimento=compartimento,
            station_alias=station_alias,
            basin_alias=basin_alias,
            grandezza=grandezza,
        )
        aggregation_statistic = 'Total'
        default_unit = 'days'
        time_aggregation_interval = 1
        time_aggregation_interval_unit = default_unit
        intended_time_spacing = 1
        intended_time_spacing_unit = default_unit
        sampled_medium = self._grandezza_sample_medium.get(grandezza, 'Air')

        if defaults:
            stat = defaults.get("aggregation_statistic")
            if stat:
                aggregation_statistic = stat
            interval_value = defaults.get("time_aggregation_interval")
            if isinstance(interval_value, (int, float)) and interval_value > 0:
                time_aggregation_interval = int(interval_value)
            interval_unit = defaults.get("time_aggregation_interval_unit")
            if interval_unit:
                time_aggregation_interval_unit = interval_unit
            spacing_value = defaults.get("intended_time_spacing")
            if isinstance(spacing_value, (int, float)) and spacing_value > 0:
                intended_time_spacing = int(spacing_value)
            spacing_unit = defaults.get("intended_time_spacing_unit")
            if spacing_unit:
                intended_time_spacing_unit = spacing_unit
            medium = defaults.get("sampled_medium")
            if medium:
                sampled_medium = medium

        ds_kwargs = {
            "name": name,
            "description": f"{grandezza_description}. Units of measure: {udm}",
            "observation_type": 'Field Observation',
            "sampled_medium": sampled_medium,
            "no_data_value": -9999,
            "aggregation_statistic": aggregation_statistic,
            "time_aggregation_interval": time_aggregation_interval,
            "status": status,
            "result_type": 'Timeseries',
            "value_count": 0,
            "phenomenon_begin_time": None,
            "phenomenon_end_time": None,
            "result_begin_time": None,
            "result_end_time": None,
            "is_visible": True,
            "is_private": False,
            "thing": site_uid,
            "sensor": sensor_uid,
            "observed_property": observed_property_uid,
            "processing_level": processing_level_uid,
            "unit": unit_uid,
            "time_aggregation_interval_unit": time_aggregation_interval_unit,
            "intended_time_spacing": intended_time_spacing,
            "intended_time_spacing_unit": intended_time_spacing_unit,
        }
        logging.info(
            "Creating datastream: name=%s site=%s observed_property=%s unit=%s",
            name, site_uid, observed_property_uid, unit_uid,
        )
        logging.debug("datastreams.create request keyword names: %s", list(ds_kwargs.keys()))
        try:
            new_ds = self.api.datastreams.create(**ds_kwargs)
            return new_ds
        except Exception:
            logging.exception(
                "Failed to create datastream: name=%s site=%s sensor=%s observed_property=%s unit=%s",
                name, site_uid, sensor_uid, observed_property_uid, unit_uid,
            )
            logging.error(
                "datastreams.create request keyword names sent: %s",
                list(ds_kwargs.keys()),
            )
            raise

    def _get_series_temporali_defaults(
        self,
        compartimento: str,
        station_alias: str,
        basin_alias: str,
        grandezza: str,
    ) -> Optional[Dict[str, Any]]:
        if not self._series_temporali_metadata:
            return None

        key = self._build_series_key(compartimento, station_alias, basin_alias, grandezza)
        if key in self._series_temporali_metadata:
            return self._series_temporali_metadata[key]

        # Fallback: try ignoring basin if not found
        if key[2]:
            fallback_key = (key[0], key[1], "", key[3])
            return self._series_temporali_metadata.get(fallback_key)

        return None

    @staticmethod
    def _normalize_key_component(value: Any) -> str:
        if value is None:
            return ""
        if isinstance(value, str):
            s = value.strip()
        else:
            if pd.isna(value):
                return ""
            s = str(value).strip()
        if not s or s.lower() == "nan":
            return ""
        return s.upper()

    @classmethod
    def _build_series_key(
        cls,
        compartimento: Any,
        station_alias: Any,
        basin_alias: Any,
        grandezza: Any,
    ) -> Tuple[str, str, str, str]:
        return (
            cls._normalize_key_component(compartimento),
            cls._normalize_key_component(station_alias),
            cls._normalize_key_component(basin_alias),
            cls._normalize_key_component(grandezza),
        )

    @staticmethod
    def _coerce_optional_string(value: Any) -> Optional[str]:
        if value is None:
            return None
        if isinstance(value, str):
            s = value.strip()
        else:
            if pd.isna(value):
                return None
            s = str(value).strip()
        return s or None

    @staticmethod
    def _parse_aggregation_period(period: Any) -> Tuple[Optional[int], Optional[str]]:
        if period is None:
            return None, None
        if isinstance(period, str):
            period_str = period.strip()
        else:
            if pd.isna(period):
                return None, None
            period_str = str(period).strip()
        if not period_str:
            return None, None

        period_str = period_str.upper()
        match = AGGREGATION_PERIOD_PATTERN.fullmatch(period_str)
        if not match:
            return None, None

        groups = {k: int(v) if v is not None else 0 for k, v in match.groupdict().items()}

        years = groups.get("years", 0)
        months = groups.get("months", 0)
        days = groups.get("days", 0)
        hours = groups.get("hours", 0)
        minutes = groups.get("minutes", 0)
        seconds = groups.get("seconds", 0)

        # If years or months are combined with smaller units, we cannot accurately convert
        if years:
            if months or days or hours or minutes or seconds:
                return None, None
            return years, "years"
        if months:
            if days or hours or minutes or seconds:
                return None, None
            return months, "months"

        total_seconds = (
            ((days * 24 + hours) * 60 + minutes) * 60 + seconds
        )
        if total_seconds == 0:
            return None, None

        unit_factors = [
            (86400, "days"),
            (3600, "hours"),
            (60, "minutes"),
            (1, "seconds"),
        ]
        for factor, unit_name in unit_factors:
            if total_seconds % factor == 0:
                value = total_seconds // factor
                if value > 0:
                    return int(value), unit_name

        return None, None

    @staticmethod
    def _apply_missing_defaults(entry: Dict[str, Any], fallback: Optional[Dict[str, Any]]) -> None:
        if not fallback:
            return
        for key, value in fallback.items():
            entry.setdefault(key, value)

    # -------------------- Utils --------------------
    def _find_files(self, patterns: List[str]) -> List[Path]:
        files: List[Path] = []
        for pat in patterns:
            if "*" in pat:
                # Search recursively in subdirectories
                files.extend(sorted(self.data_dir.rglob(pat)))
            else:
                p = self.data_dir / pat
                if p.exists():
                    files.append(p)
        # de-duplicate while preserving order
        seen = set()
        unique: List[Path] = []
        for f in files:
            if f not in seen:
                seen.add(f)
                unique.append(f)
        return unique

    @staticmethod
    def _parse_decimal_comma(value: str | float | int | None) -> Optional[float]:
        if value is None:
            return None
        if isinstance(value, (int, float)):
            return float(value)
        s = str(value).strip().replace("\u00a0", "")
        if s == "" or s.upper() == "NA":
            return None
        s = s.replace(".", "").replace(",", ".") if s.count(',') == 1 else s.replace(',', '.')
        try:
            return float(s)
        except ValueError:
            return None

    # --------------- Result Qualifier preparation and mapping ---------------
    def _prepare_result_qualifiers(self, workspace_uid: str, flag_quality: pd.DataFrame) -> None:
        if flag_quality is None or flag_quality.empty:
            return
        required_cols = {"SIGLA_STRUMENTO", "TIPO_VALORE", "FLAG_VALORE", "DESCRIZIONE_FLAG"}
        if not required_cols.issubset(set(flag_quality.columns)):
            logging.warning("FLAG_QUALITY.csv missing required columns; skipping qualifiers")
            return
        # Group by (SIGLA_STRUMENTO, FLAG_VALORE) and merge (TIPO_VALORE, DESCRIZIONE_FLAG)
        grouped = flag_quality.groupby(["SIGLA_STRUMENTO", "FLAG_VALORE"], observed=False)
        for (strumento_raw, flag_raw), gdf in grouped:
            strumento = str(strumento_raw).strip()
            flag = str(flag_raw).strip()
            if not strumento or not flag:
                continue
            # Build merged description from unique pairs
            merged_parts: List[str] = []
            seen = set()
            for _, r in gdf.iterrows():
                tipo_valore = str(r.get("TIPO_VALORE", "")).strip()
                descr = str(r.get("DESCRIZIONE_FLAG", "")).strip()
                if not tipo_valore and not descr:
                    continue
                part = f"{tipo_valore} - {descr}" if tipo_valore and descr else (tipo_valore or descr)
                if part and part not in seen:
                    seen.add(part)
                    merged_parts.append(part)
            merged_descr = "; ".join(merged_parts) if merged_parts else f"{strumento} {flag}"
            code = flag
            # Try find existing qualifier by code
            qualifier_uid = None
            for q in self.api.resultqualifiers.list(workspace=workspace_uid).items:
                if getattr(q, "code", None) == code:
                    qualifier_uid = str(q.uid)
                    break
            if qualifier_uid is None:
                q = self.api.resultqualifiers.create(
                    code=code,
                    description=merged_descr,
                    workspace=workspace_uid,
                )
                qualifier_uid = str(q.uid)
            self._result_qualifiers[(strumento, flag)] = qualifier_uid


