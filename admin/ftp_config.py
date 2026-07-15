"""
Configuration file for FTP download settings (repository administrators).
"""

from pathlib import Path

# FTP Server Configuration
FTP_HOST = "ftp.essi-lab.eu"
FTP_REMOTE_DIR = "/06_Cartella_DBv1_completi"

# Local Configuration (repo-root data folder)
_REPO_ROOT = Path(__file__).resolve().parent.parent
LOCAL_DIR = str(_REPO_ROOT / "data")

# Download Options
REMOVE_ZIP_AFTER_EXTRACT = False  # Set to True to delete zip files after extraction
CHUNK_SIZE = 8192  # FTP download chunk size in bytes
FORCE_OVERWRITE = True  # Set to True to always overwrite existing files
