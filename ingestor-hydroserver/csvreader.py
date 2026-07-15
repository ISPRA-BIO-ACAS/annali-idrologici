
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
