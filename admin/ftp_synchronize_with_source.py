#!/usr/bin/env python3
"""
Synchronize local data with the ISPRA FTP source (repository admin use only).
"""

import ftplib
import logging
import argparse
from pathlib import Path
from typing import List, Tuple
from ftp_config import FTP_HOST, FTP_REMOTE_DIR, LOCAL_DIR, CHUNK_SIZE, FORCE_OVERWRITE

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


class FTPDownloader:
    def __init__(self, host: str, username: str, password: str, remote_dir: str, local_dir: str):
        self.host = host
        self.username = username
        self.password = password
        self.remote_dir = remote_dir
        self.local_dir = Path(local_dir)
        self.ftp = None
        self.downloaded_files = []

    def connect(self) -> bool:
        """Connect to FTP server."""
        try:
            logger.info(f"Connecting to FTP server: {self.host}")
            self.ftp = ftplib.FTP(self.host)
            self.ftp.login(self.username, self.password)
            self.ftp.cwd(self.remote_dir)
            logger.info(f"Connected to FTP server {self.host}, remote directory: {self.remote_dir}")
            return True
        except Exception as e:
            logger.error(f"Failed to connect to FTP server: {e}")
            return False

    def disconnect(self):
        """Disconnect from FTP server."""
        if self.ftp:
            try:
                self.ftp.quit()
                logger.info("Disconnected from FTP server")
            except Exception:
                pass

    def list_directory(self, remote_path: str = "") -> List[Tuple[str, int, str]]:
        """List all files and directories in the remote directory with their sizes and types."""
        try:
            if remote_path:
                self.ftp.cwd(remote_path)

            try:
                nlst_files = []
                self.ftp.retrlines('NLST', nlst_files.append)
                logger.debug(f"NLST output for {remote_path or 'root'}: {nlst_files}")
            except Exception as e:
                logger.debug(f"NLST failed: {e}")
                nlst_files = []

            files = []
            self.ftp.retrlines('LIST', files.append)

            logger.debug(f"Raw FTP LIST output for {remote_path or 'root'}:")
            for line in files:
                logger.debug(f"  {line}")

            file_list = []
            for line in files:
                parts = line.split()
                if len(parts) >= 9:
                    file_name = ' '.join(parts[8:])
                    if file_name in ['.', '..']:
                        continue

                    try:
                        file_size = int(parts[4])
                        file_type = parts[0][0]
                        logger.debug(f"Found: {file_name} (size: {file_size}, type: {file_type})")
                        file_list.append((file_name, file_size, file_type))
                    except (ValueError, IndexError) as e:
                        logger.debug(f"Error parsing line '{line}': {e}")
                        file_list.append((file_name, 0, '-'))
                else:
                    logger.debug(f"Skipping line with insufficient parts: {line}")

            if not file_list and nlst_files:
                logger.debug("Using NLST results as fallback")
                for file_name in nlst_files:
                    if file_name not in ['.', '..']:
                        current_dir = self.ftp.pwd()
                        try:
                            self.ftp.cwd(file_name)
                            self.ftp.cwd(current_dir)
                            file_list.append((file_name, 0, 'd'))
                            logger.debug(f"NLST found directory: {file_name}")
                        except Exception:
                            file_list.append((file_name, 0, '-'))
                            logger.debug(f"NLST found file: {file_name}")

            for item_name, item_size, item_type in file_list[:]:
                if item_type == 'd':
                    current_dir = self.ftp.pwd()
                    try:
                        sub_nlst = []
                        self.ftp.retrlines(f'NLST {item_name}', sub_nlst.append)
                        logger.debug(f"NLST for subdirectory {item_name}: {sub_nlst}")
                        if sub_nlst:
                            logger.info(f"Directory {item_name} contains {len(sub_nlst)} items: {sub_nlst}")
                            for sub_file in sub_nlst:
                                file_list.append((sub_file, 0, '-'))
                                logger.debug(f"Added subdirectory file: {sub_file}")
                    except Exception as e:
                        logger.debug(f"NLST failed for subdirectory {item_name}: {e}")
                    finally:
                        try:
                            self.ftp.cwd(current_dir)
                        except Exception:
                            pass

            logger.info(f"Found {len(file_list)} items in {remote_path or 'root'}")
            return file_list
        except Exception as e:
            logger.error(f"Failed to list directory {remote_path}: {e}")
            return []

    def download_file(self, remote_file: str, local_file: Path, file_size: int = 0) -> bool:
        """Download a single file from FTP."""
        try:
            local_file.parent.mkdir(parents=True, exist_ok=True)

            if not FORCE_OVERWRITE and local_file.exists() and file_size > 0:
                if local_file.stat().st_size == file_size:
                    logger.info(f"Skipping existing file: {remote_file}")
                    return True

            logger.info(f"Downloading: {remote_file} ({file_size:,} bytes)")

            with open(local_file, 'wb') as f:
                self.ftp.retrbinary(f'RETR {remote_file}', f.write, blocksize=CHUNK_SIZE)

            if file_size > 0 and local_file.stat().st_size != file_size:
                logger.warning(
                    f"Size mismatch for {remote_file}: expected {file_size}, got {local_file.stat().st_size}"
                )
                return False

            self.downloaded_files.append(local_file)
            logger.info(f"Downloaded: {remote_file} -> {local_file}")
            return True

        except Exception as e:
            logger.error(f"Failed to download {remote_file}: {e}")
            return False

    def download_recursive(self, remote_path: str = "", local_path: Path = None) -> int:
        """Recursively download all files and directories."""
        if local_path is None:
            local_path = self.local_dir

        if not self.ftp:
            logger.error("FTP connection not established")
            return 0

        current_dir = self.ftp.pwd()

        try:
            if remote_path:
                try:
                    self.ftp.cwd(remote_path)
                except Exception as e:
                    logger.warning(f"Cannot access directory {remote_path}: {e}")
                    return 0

            items = self.list_directory(remote_path)
            if not items:
                logger.warning(f"No items found in directory: {remote_path or 'root'}")
                return 0

            success_count = 0

            for item_name, item_size, item_type in items:
                logger.debug(f"Processing: {item_name} (type: {item_type}, size: {item_size})")
                if item_type == 'd':
                    logger.info(f"Entering directory: {item_name}")
                    local_dir = local_path / item_name
                    try:
                        local_dir.mkdir(parents=True, exist_ok=True)
                        logger.debug(f"Created local directory: {local_dir}")
                    except FileExistsError:
                        logger.debug(f"Directory already exists: {local_dir}")

                    sub_remote_path = f"{remote_path}/{item_name}" if remote_path else item_name
                    logger.debug(f"Recursing into: {sub_remote_path}")
                    try:
                        success_count += self.download_recursive(sub_remote_path, local_dir)
                    except Exception as e:
                        logger.warning(f"Error recursing into {sub_remote_path}: {e}")

                else:
                    if '/' in item_name:
                        subdir_name = item_name.split('/')[0]
                        filename = item_name.split('/')[1]
                        local_file = local_path / subdir_name / filename
                        local_file.parent.mkdir(parents=True, exist_ok=True)
                    else:
                        local_file = local_path / item_name

                    logger.debug(f"Downloading file: {item_name} -> {local_file}")
                    if self.download_file(item_name, local_file, item_size):
                        success_count += 1

            return success_count

        except Exception as e:
            logger.error(f"Error in directory {remote_path}: {e}")
            return 0
        finally:
            try:
                self.ftp.cwd(current_dir)
            except Exception:
                pass

    def download_all_files(self) -> bool:
        """Download all files from the remote directory recursively."""
        if not self.connect():
            return False

        try:
            logger.info("Starting recursive download...")
            success_count = self.download_recursive()

            if success_count > 0:
                logger.info("Download summary:")
                logger.info(f"  Downloaded: {success_count} files")
                return True

            logger.warning("No files were downloaded")
            return False
        finally:
            self.disconnect()

    def _list_recursive(self, remote_path: str = "", prefix: str = ""):
        """Recursively list directory contents for dry-run."""
        if not self.ftp:
            logger.error("FTP connection not established")
            return

        current_dir = self.ftp.pwd()

        try:
            if remote_path:
                try:
                    self.ftp.cwd(remote_path)
                except Exception as e:
                    logger.warning(f"Cannot access directory {remote_path}: {e}")
                    return

            items = self.list_directory(remote_path)

            for item_name, item_size, item_type in items:
                if item_type == 'd':
                    logger.info(f"{prefix}📁 {item_name}/")
                    sub_remote_path = f"{remote_path}/{item_name}" if remote_path else item_name
                    try:
                        self._list_recursive(sub_remote_path, prefix + "  ")
                    except Exception as e:
                        logger.warning(f"Cannot access subdirectory {sub_remote_path}: {e}")
                else:
                    logger.info(f"{prefix}📄 {item_name} ({item_size:,} bytes)")

        except Exception as e:
            logger.error(f"Error listing directory {remote_path}: {e}")
        finally:
            try:
                self.ftp.cwd(current_dir)
            except Exception:
                pass

    def print_file_tree(self):
        """Print a tree view of downloaded files."""
        logger.info("File structure:")
        self._print_tree(self.local_dir, prefix="")

    def _print_tree(self, path: Path, prefix: str):
        """Recursively print directory tree."""
        if path.is_file():
            size = path.stat().st_size
            logger.info(f"{prefix}📄 {path.name} ({size:,} bytes)")
        elif path.is_dir():
            logger.info(f"{prefix}📁 {path.name}/")
            items = sorted(path.iterdir())
            for i, item in enumerate(items):
                is_last = i == len(items) - 1
                new_prefix = prefix + ("└── " if is_last else "├── ")
                self._print_tree(item, new_prefix)


def main():
    """Synchronize local data with the ISPRA FTP source."""
    parser = argparse.ArgumentParser(
        description="Synchronize local data with the ISPRA FTP source (repository admin use only)"
    )
    parser.add_argument("--dry-run", action="store_true", help="List files without downloading")
    parser.add_argument("--verbose", "-v", action="store_true", help="Enable verbose logging")
    parser.add_argument("--force", action="store_true", help="Force overwrite existing files")
    parser.add_argument("--username", required=True, help="FTP username")
    parser.add_argument("--password", required=True, help="FTP password")

    args = parser.parse_args()

    if args.verbose:
        logging.getLogger().setLevel(logging.DEBUG)

    global FORCE_OVERWRITE
    if args.force:
        FORCE_OVERWRITE = True
        logger.info("Force overwrite enabled via command line")

    local_path = Path(LOCAL_DIR)
    local_path.mkdir(parents=True, exist_ok=True)

    logger.info(f"Starting synchronization to {LOCAL_DIR}")
    logger.info(f"FTP Server: {FTP_HOST}")
    logger.info(f"Remote Directory: {FTP_REMOTE_DIR}")
    logger.info(f"Force Overwrite: {FORCE_OVERWRITE}")

    downloader = FTPDownloader(
        host=FTP_HOST,
        username=args.username,
        password=args.password,
        remote_dir=FTP_REMOTE_DIR,
        local_dir=LOCAL_DIR
    )

    if args.dry_run:
        if downloader.connect():
            logger.info("Scanning remote directory structure...")
            downloader._list_recursive("")
            downloader.disconnect()
        return 0

    success = downloader.download_all_files()

    if success:
        logger.info("Synchronization completed successfully!")
        downloader.print_file_tree()
        return 0

    logger.error("Synchronization failed!")
    return 1


if __name__ == "__main__":
    exit(main())
