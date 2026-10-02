# Credit Risk — OMS / dispatch integration

Call **before** confirming or dispatching an order so bad credit is blocked at the gate.

## Evaluate one order

`POST /api/credit-risk/orders/evaluate`

```json
{
  "customer": "Acme Traders",
  "orderAmount": 50000,
  "referenceId": "OMS-12345",
  "reserveExposure": true
}
```

- `referenceId` + `reserveExposure: true` reserves amount against the customer limit until released.
- Response includes `orderDecision`, `paymentDecision`, `requiredPayment`, `auditId`.

## Batch Excel

`POST /api/credit-risk/orders/evaluate-excel` (multipart file) — same columns as Order Excel on the UI.

## Manager override

`POST /api/credit-risk/orders/override` (permission: `creditRiskDecisionOverride`)

```json
{
  "auditId": "<from evaluate response>",
  "newDecision": "TAKE_ORDER",
  "reason": "MD approved — payment tomorrow"
}
```

## Insights (UI + API)

- `GET /api/credit-risk/insights/committee` — counts, overdue in queue, wind-down list size.
- `GET /api/credit-risk/insights/collect-today` — customers to call today (CSV / WhatsApp from UI).
