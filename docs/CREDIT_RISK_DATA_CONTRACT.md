# Credit Risk Engine — Extra Data Contract

Rules that cannot be derived from current Detailed Sales / Receivable Ageing uploads
need the columns / feeds below.

## 1. Invoice due date OR payment terms (hybrid)

Preferred on **Detailed Sales Invoices**:

| Column examples | Purpose |
|-----------------|---------|
| `Due Date` / `Invoice Due Date` | Exact due date for on-time % and overdue |
| `Payment Terms` / `Credit Days` | Days from invoice date if due date missing |

**Fallback order:** Due Date → row Payment Terms → **customer `paymentTermsDays` on master** → category default (A:30, B:15, semi-wholesale:45) → invoice-age proxy.

## 2. Customer Ledger (third file on Upload Files) — **provide this**

On **Upload Files** (with Detailed Sales + Receivable Ageing), choose **CustomerLedger** — `.xlsx` only (same export as `CustomerLedger.xlsx`).

**Row 1 (optional):** title `Customer Ledger`  
**Header row (required):**

| Column | Required |
|--------|----------|
| Customer | yes |
| Transaction Type | yes |
| Voucher Date | yes (or Accounting Date) |
| Voucher No. | recommended |
| Accounting Date | recommended |
| Amount | yes |
| Opening Balance / Debit / Credit / Closing Balance | as in export |

- Only this template is accepted (old “Payment Status” sheets are rejected).
- Stored in Mongo `credit_payment_event_uploads` with `format=CUSTOMER_LEDGER` and `dataRowCount`.
- **Bounce / fail:** counted when **Transaction Type** contains fail, bounce, return, dishonour, reject (within config window). Normal `Invoice` / `Invoice Payment` rows do not count as failures.

Unlocks: `PAYMENT_FAILURE_HISTORY` when bounce-type rows exist.

## 3. Payment promise = Outstanding Due payment date + per-invoice cash collect

**Overall:** `nextPaymentDate` on Outstanding Due is the customer-level promise.

**Per invoice / cash-in-N-days:** on Credit Risk → **Cash / collect by** (amount + days), e.g. new invoice ₹X cash in 10 days.

- Open items → alert `COLLECT_CASH_DUE` + action queue **Need payment**
- Past due / missed → `PAYMENT_PROMISE_BROKEN` (order rules + score)

**Excel path:** Detailed Sales columns `Due Date` / `Credit Days` / `Payment Terms` still apply per invoice row when present (before customer/category defaults).

## 4. Margin / profitability (optional)

| Column examples | Purpose |
|-----------------|---------|
| `Margin` / `Gross Profit` / `%` | Business score margin component |

Until provided: margin weight redistributed or scored as neutral (config).

## 5. Void / cancelled / credit note

| Column examples | Purpose |
|-----------------|---------|
| `Status` containing void/cancel | Exclude from risk |
| `Credit Note Amount` | Net exposure correctly |

## 6. Order Excel (batch evaluate)

| Column | Required |
|--------|----------|
| Customer / Customer Name / Customer ID | yes |
| Order Amount / Amount | yes |
| Order Ref / Reference Id / OMS id | optional (for reserve) |

`POST /api/credit-risk/orders/evaluate-excel` → decisions `.xlsx`  
OMS: `POST /api/credit-risk/orders/evaluate` with `referenceId` + `reserveExposure=true`

## 7. Recommended credit limit

One-click **Apply recommended limit** on Credit Risk (needs `customerLimitEdit` + Details/Outstanding access).

## Live rules (current)

- Overdue hard-block by category: A:90, B:60, semi-wholesale:120 (C blocked separately)
- Credit limit breach: soft for RELIABLE+; hard for WATCH/RISKY (`BY_RISK_CATEGORY`)
- Snapshots rebuilt after sales/ageing upload and after payments upload
- Action queue on `/credit-risk`
