# Yearbooks HydroServer ingestor
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

import csv
from pathlib import Path
from typing import Iterator, Dict

class CSVReader:
    def __init__(self, filepath: str | Path, delimiter: str = ";"):
        self.filepath = Path(filepath)
        self.delimiter = delimiter

    def rows(self) -> Iterator[Dict[str, str]]:
        """
        Yield one row at a time as a dictionary {column_name: value}.
        """
        with self.filepath.open(newline="", encoding="utf-8") as f:
            reader = csv.DictReader(f, delimiter=self.delimiter)
            for row in reader:
                yield row
