# Google Drive Excel → next payment dates

Outstanding Due can sync **customer name + next payment date** with one workbook in Google Drive (`.xlsx` upload or native Google Sheet).

- **Two-way sync** on login and **Sync now**: Drive → app, then app → Drive
- **App → Drive** when you edit a due date in Outstanding Due (after a short debounce)

Blank Excel date cells are ignored on import (they do not clear app dates). Unknown names are listed, not created.

## 1. Excel layout

One sheet (first matching sheet, or set `GOOGLE_DRIVE_SHEET_NAME`).

| Customer Name | Next Payment Date | Phone (optional) |
|---|---|---|
| ABC Traders | 18-08 | 9876543210 |

- Header row must include a customer name column and a next-payment / due date column.
- Dates: `DD-MM` (same as the app), `DD-MMM-YY` (e.g. `19-Aug-26`), `DD/MM/YYYY`, or a real Excel date cell.
- Keep this the **same Drive file** (do not upload a copy with a new link).
- Save the file in Drive; **login** and **Sync now** both run two-way sync.

## 2. Google Cloud (once)

1. Open [Google Cloud Console](https://console.cloud.google.com/) and create or pick a project.
2. **APIs & Services → Library** → enable **Google Drive API** and **Google Sheets API**.
3. **APIs & Services → Credentials → Create credentials → Service account**.
   - Name e.g. `openproject-drive-sync`.
   - Skip optional roles.
4. Open the service account → **Keys → Add key → Create new key → JSON**. Download the file.
5. Copy the service account email (`...@....iam.gserviceaccount.com`).

## 3. Share the Excel

1. In Google Drive, open the `.xlsx`.
2. **Share** → paste the service account email → **Editor** → uncheck “Notify” → Share.
3. Copy the file id from the URL:  
   `https://drive.google.com/file/d/`**`THIS_IS_THE_ID`**`/view`

Do **not** use “Anyone with the link” as the only access. The service account must be a direct **Editor** (read + write for app push-back).

## 4. Local `.env.local.properties`

```
google.drive.sync.enabled=true
google.drive.sync.file-id=THIS_IS_THE_ID
google.drive.sync.service-account-json={"type":"service_account",...whole json on one line...}
```

You can also point at the downloaded file (no extra quotes). Relative names resolve from the process working directory:

```
google.drive.sync.service-account-json=/absolute/path/google-service-account.json
google.drive.sync.service-account-json=google-service-account.json
```

Restart the backend. On Outstanding Due click the spreadsheet icon → **Sync now**.

## 5. Render (production)

Dashboard → `openproject-backend` → Environment:

| Variable | Value |
|---|---|
| `GOOGLE_DRIVE_SYNC_ENABLED` | `true` |
| `GOOGLE_DRIVE_FILE_ID` | file id |
| `GOOGLE_SERVICE_ACCOUNT_JSON` | entire JSON, **or** base64 of the JSON (no wrapping quotes) |

Redeploy after saving. Sync also runs automatically after each login. Use **Sync now** if you need dates refreshed without signing in again.

Optional: `GOOGLE_DRIVE_SHEET_NAME` if the workbook has several tabs.

## 6. Permissions

- Viewing the Drive panel: Outstanding Due access.
- **Sync now**: **Edit Due Dates** (`paymentDateEdit`).

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `Google Sheets write denied` / 403 | Enable **Google Sheets API** in Google Cloud and share the sheet as **Editor** |
| `Drive file not found` / 404 | Wrong file id, or file not shared with the service account |
| `Drive access denied` / 403 | Share as **Editor** with the exact service account email |
| Unmatched names | Excel name does not match customer master; add Phone column |
| Invalid dates | Use `DD-MM`, `DD-MMM-YY` (e.g. `19-Aug-26`), or a real Excel date |
| Sync skipped | Drive file unchanged since last successful sync — click **Sync now** to force a re-apply |
