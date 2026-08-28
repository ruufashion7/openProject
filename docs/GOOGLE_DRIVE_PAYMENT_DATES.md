# Google Drive Excel → next payment dates

Outstanding Due can sync **customer ID + name + phone + next payment date + latest note** with one workbook in Google Drive (`.xlsx` upload or native Google Sheet).

- **Two-way sync** (Drive → app, then app → Drive) runs automatically on **login**, after a successful **receivable ageing upload**, and when you click **Sync now** on Outstanding Due
- **App → Drive** when you edit a due date in Outstanding Due, or add/edit/delete a customer note (after a short debounce)
- Sync errors are stored and shown in the Drive sync panel (pull failures stop before push; push failures keep pull results)

Blank Excel date cells are ignored on import (they do not clear app dates). Blank Notes cells are ignored on import (they do not delete app notes). Rows in Drive that do not match any app customer are left alone (manual entries).

## Payment sheet behaviour

**Who gets a Drive row:** On sync, the app adds any **Outstanding Due** customer who is missing from the sheet (receivable balance > 0, plus **retained** cash customers at ₹0). Ignored customers are skipped.

**Who is removed:** When a customer is **fully paid** (no longer on Outstanding Due) and is not retained, their row is **removed** from Drive on the next sync.

**Sort order:** Rows are sorted **high → low by outstanding amount** from the app (latest receivable ageing upload). Retained customers with ₹0 appear at the bottom. Amount is **not** written to Drive unless your sheet already has an Outstanding Amount column.

**Matching rows (in order):**

1. **Customer ID** — stable internal key (e.g. `abc traders`)
2. **Customer Name** — exact, then fuzzy match
3. **Phone Number** — synced both ways; not used to match rows

## 1. Excel layout

One sheet (first matching sheet, or set `GOOGLE_DRIVE_SHEET_NAME`).

| Customer ID | Customer Name | Phone Number | Next Payment Date | Notes |
|---|---|---|---|---|
| abc traders | ABC Traders | 9876543210 | 18-08 | Call Monday |
| cash customer | Cash Customer | 9123456789 | 20-08 | Retained |

- Header row must include a customer name column and a next-payment / due date column.
- **Customer ID** is optional but recommended. The app writes it automatically in the first column when missing. Do not edit unless you know what you are doing.
- **Phone Number** syncs both ways; blank cells in Drive do not clear app phones.
- **Notes** — latest note only; new Drive notes are appended in the app (max 6).
- Dates: `DD-MM`, `DD-MMM-YY`, `DD/MM/YYYY`, or a real Excel date cell.
- Optional: if your sheet already has an **Outstanding Amount** column, the app will keep it updated. It is not added automatically.

## 2. Google Cloud (once)
