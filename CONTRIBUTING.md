### Syncing data with ISPRA FTP

Admin scripts live in `admin/`. GitHub repo admins synchronize the local `data/` folder with the ISPRA FTP source in case updated data is made available (e.g. additional compartments are available, or updated quality flags). Admin use `admin/ftp_synchronize_with_source.py`. This script requires FTP credentials.

```bash
python3 admin/ftp_synchronize_with_source.py --username YOUR_FTP_USER --password YOUR_FTP_PASSWORD
```

FTP host and remote directory are configured in `admin/ftp_config.py`.


## Creating a new release

In case additional data is now present or modified FROST server or HydroServer ingestor tools are available, commit all the modified files and push to GitHub.

After committing and pushing all changes create a new release:

```bash
git tag v1.0.0
git push origin v1.0.0

gh release create v1.0.0 --title "Version 1.0.0" --notes "..."
```

Publishing a GitHub Release automatically triggers the GitHub–Zenodo integration.

Zenodo archives the repository snapshot corresponding to the release, creates a new version of the dataset, and assigns a new version-specific DOI. The Concept DOI remains unchanged and always resolves to the latest version.
