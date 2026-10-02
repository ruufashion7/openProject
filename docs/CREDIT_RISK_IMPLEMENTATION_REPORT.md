# Credit Risk Engine — Implementation Report

## 1. Files changed (important)

- [`PaymentDateOverride.java`](src/main/java/org/example/payment/PaymentDateOverride.java) — `manualHold*` fields
- [`PaymentDateOverrideCopy.java`](src/main/java/org/example/payment/PaymentDateOverrideCopy.java) — preserve hold + `withManualHold`
- [`ExcelUploadHeaderRules.java`](src/main/java/org/example/upload/ExcelUploadHeaderRules.java) — due date, terms, status, margin, CN headers
- [`UserPermissions.java`](src/main/java/org/example/auth/UserPermissions.java) / Helper / SessionPermissions / V2Migration
- Frontend: `auth.service.ts`, `permissions.config.ts`, `permission.service.ts`, `api.service.ts`, `app.routes.ts`, sidebar, page-title
- Tests updated for new `PaymentDateOverride` constructor arity

## 2. New files

| Path | Why |
|------|-----|
| `docs/CREDIT_RISK_DATA_CONTRACT.md` | Extra columns needed for full fidelity |
| `org.example.creditrisk.*` | Metrics, scoring, rules, payment decision, config, audit, snapshot, reservation, orchestrator |
| `CreditRiskController` | REST under `/api/credit-risk` |
| `frontend/.../credit-risk/*` | New UI page |
| Unit tests under `src/test/.../creditrisk/` | Scoring + order rules |

## 3. Database changes

Mongo collections (auto-created on first write):

- `customer_master` — new fields `manualHold`, `manualHoldReason`, `manualHoldAt`, `manualHoldBy`
- `app_settings` doc id `credit_risk_engine_config`
- `credit_risk_snapshots` (unique `customerKey`)
- `credit_decision_audit`
- `credit_exposure_reservations` (unique `customerKey`, TTL entries)

## 4. APIs

| Method | Path |
|--------|------|
| GET | `/api/credit-risk/customers` |
| POST | `/api/credit-risk/customers/summary` |
| POST | `/api/credit-risk/orders/evaluate` |
| POST | `/api/credit-risk/orders/override` |
| GET/PUT | `/api/credit-risk/config` |
| GET | `/api/credit-risk/audit` |
| POST | `/api/credit-risk/customers/manual-hold` |

Permissions: `creditRiskPage`, `creditRiskConfigEdit`, `creditRiskManualHold`, `creditRiskDecisionOverride`.

## 5. Rules

| Code | Severity |
|------|----------|
| MANUAL_HOLD | HARD_BLOCK → decision MANUAL_HOLD |
| CUSTOMER_STATUS_C | HARD_BLOCK |
| INVOICE_OVERDUE_120_DAYS | HARD_BLOCK when max overdue **>** configured days (default 120) |
| NO_PAYMENT_AGAINST_DUE_INVOICES_30_DAYS | HARD_BLOCK (flagged off until due-date+payment feed) |
| CREDIT_LIMIT_EXCEEDED | HARD_BLOCK or PAYMENT_REQUIRED (config) |
| NEW_CUSTOMER_NO_PAYMENT_HISTORY | HARD_BLOCK |
| MULTIPLE_OVERDUE_INVOICES, HIGH_OVERDUE_AMOUNT, LOW_ON_TIME_PAYMENT, REPEATED_LATE_PAYMENTS, HIGH_CREDIT_UTILIZATION, ORDER_AMOUNT_SIGNIFICANTLY_ABOVE_HISTORY | APPROVAL |
| PAYMENT_FAILURE_HISTORY, PAYMENT_PROMISE_BROKEN | APPROVAL when enabled |
| Alert codes | RISK_SIGNAL |

## 6. Scoring

- Payment score ≤ 60: on-time (20), avg delay (10), overdue amt (10), utilization (10), failures (5), ageing (5)
- Business score ≤ 40: frequency (10), sales 90d (10), tenure (5), consistency (5), returns (5), margin (5; neutral if missing)
- Categories: VERY_RELIABLE / RELIABLE / WATCH / RISKY / HIGH_RISK
- Payment behaviour GOOD/AVERAGE/POOR; business value HIGH/MEDIUM/LOW
- Hard rules override score for order decision

## 7. Configuration

Stored in `credit_risk_engine_config` (defaults in `CreditRiskEngineConfigDocument`). Editable on Credit Risk page with `creditRiskConfigEdit`.

## 8. Tests

`CustomerRiskScoringServiceTest`, `CreditRiskRuleEngineTest` cover score caps/categories and order rules including C, hold, 119/120/121 overdue, credit breach, new customer, unusual order, multi-reason, reserved exposure. Ran successfully with related PaymentDateOverride tests.

## 9. Existing data gaps

See [`docs/CREDIT_RISK_DATA_CONTRACT.md`](docs/CREDIT_RISK_DATA_CONTRACT.md). Until due dates / payment events / promises / margin arrive:

- On-time % and delay use invoice-age proxy on open dues
- `enableNoPaymentAgainstDueRule`, `enablePaymentFailureRule`, `enablePaymentPromiseRule` default **false**
- UI shows `dataQualityWarnings`

## 10. Assumptions

- Order = simulated amount on `/credit-risk` (no native OMS)
- Customer id = `customerKey`
- Overdue hard block is **strictly greater than** threshold days
- Concurrent exposure only with `referenceId` + `reserveExposure=true`
- Category C remains hard block + zero category limit

## 11. Example scenarios

| Scenario | Expected |
|----------|----------|
| Normal within limit | TAKE_ORDER + CREDIT |
| Category C | DO_NOT_TAKE_ORDER |
| Manual hold | MANUAL_HOLD |
| 121+ day overdue | DO_NOT_TAKE_ORDER |
| Projected over limit | TAKE_ORDER_AFTER_PAYMENT + PARTIAL_ADVANCE |
| New + ≥3 inv + 0 pay | DO_NOT_TAKE_ORDER |
| Multiple soft signals | TAKE_ORDER_WITH_APPROVAL |

## UI

Route **`/credit-risk`**: search (name/phone), risk dashboard, score breakdown, order simulator, manual hold, config (authorized).
