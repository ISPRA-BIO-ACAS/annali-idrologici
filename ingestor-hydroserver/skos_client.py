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

from __future__ import annotations

import json
import logging
import threading
from dataclasses import dataclass
from typing import Dict, Optional

import requests
from numpy.lib.recfunctions import rec_drop_fields

DEFAULT_SPARQL_ENDPOINT = "http://codes.wmo.int/system/query"
SKOS_PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel"
RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label"

@dataclass(frozen=True)
class SkosQueryResult:
    concept_uri: str
    pref_label: Optional[str]


class SkosClient:
    """Simple SKOS helper to retrieve prefLabels from a SPARQL endpoint."""

    def __init__(
        self,
        endpoint: str = DEFAULT_SPARQL_ENDPOINT,
        timeout: int = 10,
    ) -> None:
        self.endpoint = endpoint
        self.timeout = timeout
        self._cache: Dict[str, Optional[str]] = {}
        self._lock = threading.Lock()

    def get_pref_label(self, concept_uri: str, lang: Optional[str] = None) -> Optional[str]:
        if not concept_uri or not isinstance(concept_uri, str):
            return None

        concept_uri = concept_uri.strip()
        if not concept_uri:
            return None

        with self._lock:
            if concept_uri in self._cache:
                return self._cache[concept_uri]


        query = """
        SELECT ?label WHERE {
            <%s> <%s> ?label 
        }
        LIMIT 1
        """ % (concept_uri, RDFS_LABEL)

        params = {
            "output": "json",
            "format": "application/json",
            "timeout": "0",
            "query": query,
        }

        try:
            response = requests.get(self.endpoint, params=params, timeout=self.timeout)
            response.raise_for_status()
            data = response.json()
            label = self._extract_label(data)
        except (requests.RequestException, json.JSONDecodeError):
            logging.warning("Failed to fetch SKOS prefLabel for %s", concept_uri, exc_info=True)
            label = None

        with self._lock:
            self._cache[concept_uri] = label

        return label

    @staticmethod
    def _extract_label(payload: Dict) -> Optional[str]:
        results = payload.get("results", {})
        bindings = results.get("bindings", [])
        for binding in bindings:
            label_info = binding.get("label")
            if not isinstance(label_info, dict):
                continue
            value = label_info.get("value")
            if value:
                return str(value)
        return None

