package org.example.creditrisk;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class CustomerRiskScoringService {

    public CustomerRiskScore score(CustomerRiskMetrics m, CreditRiskEngineConfigDocument cfg) {
        List<ScoreComponent> payment = new ArrayList<>();
        List<ScoreComponent> business = new ArrayList<>();

        payment.add(onTime(m));
        payment.add(avgDelay(m));
        payment.add(overdueAmount(m));
        payment.add(utilization(m, cfg));
        payment.add(failures(m));
        payment.add(ageing(m));

        business.add(frequency(m));
        business.add(sales90(m));
        business.add(tenure(m));
        business.add(returns(m));
        business.add(margin(m, cfg));

        double p = payment.stream().mapToDouble(ScoreComponent::scoreObtained).sum();
        double b = business.stream().mapToDouble(ScoreComponent::scoreObtained).sum();
        p = Math.min(60, p);
        b = Math.min(40, b);
        double total = p + b;
        String category = riskCategory(total, cfg);
        String behaviour = paymentBehaviour(m);
        String value = businessValue(m);

        return CustomerRiskScore.of(p, b, category, behaviour, value, payment, business);
    }

    private static ScoreComponent onTime(CustomerRiskMetrics m) {
        double pct = m.getOnTimePaymentPercentage();
        double pts;
        if (m.getPaidInvoices() == 0) {
            pts = 10;
        } else if (pct >= 95) {
            pts = 20;
        } else if (pct >= 85) {
            pts = 16;
        } else if (pct >= 70) {
            pts = 12;
        } else if (pct >= 50) {
            pts = 6;
        } else {
            pts = 2;
        }
        return new ScoreComponent("On-time payment %", String.format("%.1f%%", pct), pts, 20,
                "Share of current outstanding not past due (by amount)");
    }

    private static ScoreComponent avgDelay(CustomerRiskMetrics m) {
        double d = m.getAveragePaymentDelayDays();
        double pts;
        if (d <= 0) {
            pts = 10;
        } else if (d <= 7) {
            pts = 8;
        } else if (d <= 15) {
            pts = 5;
        } else if (d <= 30) {
            pts = 2;
        } else {
            pts = 0;
        }
        return new ScoreComponent("Average payment delay", String.format("%.1f days", d), pts, 10,
                "Amount-weighted delay on open overdue (ignores tiny open dues)");
    }

    private static ScoreComponent overdueAmount(CustomerRiskMetrics m) {
        double o = m.getOverdueAmount();
        double pts;
        if (o <= 0) {
            pts = 10;
        } else if (o < 50_000) {
            pts = 7;
        } else if (o < 200_000) {
            pts = 4;
        } else if (o < 500_000) {
            pts = 2;
        } else {
            pts = 0;
        }
        return new ScoreComponent("Overdue amount", String.format("%.0f", o), pts, 10, "Current overdue exposure");
    }

    private static ScoreComponent utilization(CustomerRiskMetrics m, CreditRiskEngineConfigDocument cfg) {
        Double u = m.getCreditUtilization();
        double pts;
        if (u == null) {
            pts = 7;
        } else if (u < cfg.getCreditUtilizationWatchPercent()) {
            pts = 10;
        } else if (u < cfg.getCreditUtilizationHighPercent()) {
            pts = 5;
        } else {
            pts = 1;
        }
        return new ScoreComponent("Credit utilization", u == null ? "n/a" : String.format("%.1f%%", u), pts, 10,
                "Outstanding / credit limit");
    }

    private static ScoreComponent failures(CustomerRiskMetrics m) {
        int f = m.getPaymentFailuresLast90Days();
        double pts = f == 0 ? 5 : f == 1 ? 2 : 0;
        return new ScoreComponent("Payment failures", String.valueOf(f), pts, 5, "Failed/bounced payments (when data present)");
    }

    private static ScoreComponent ageing(CustomerRiskMetrics m) {
        int days = m.getMaximumOverdueDays();
        double pts;
        if (days <= 0) {
            pts = 5;
        } else if (days <= 30) {
            pts = 4;
        } else if (days <= 60) {
            pts = 3;
        } else if (days <= 90) {
            pts = 1;
        } else {
            pts = 0;
        }
        return new ScoreComponent("Outstanding ageing", days + " days max", pts, 5,
                "Max overdue days on open lines above min due (₹)");
    }

    private static ScoreComponent frequency(CustomerRiskMetrics m) {
        double f = m.getOrderFrequencyPerMonth();
        double pts;
        if (f >= 4) {
            pts = 15;
        } else if (f >= 2) {
            pts = 11;
        } else if (f >= 1) {
            pts = 7;
        } else if (f > 0) {
            pts = 3;
        } else {
            pts = 0;
        }
        return new ScoreComponent("Orders per month", String.format("%.2f", f), pts, 15, "Average invoices per month since first order");
    }

    private static ScoreComponent sales90(CustomerRiskMetrics m) {
        double s = m.getSalesLast90Days();
        double pts;
        if (s >= 1_000_000) {
            pts = 10;
        } else if (s >= 500_000) {
            pts = 7;
        } else if (s >= 100_000) {
            pts = 4;
        } else if (s > 0) {
            pts = 2;
        } else {
            pts = 0;
        }
        return new ScoreComponent("Sales last 90 days", String.format("%.0f", s), pts, 10, "Recent business volume");
    }

    private static ScoreComponent tenure(CustomerRiskMetrics m) {
        int d = m.getCustomerTenureDays();
        double pts;
        if (d >= 730) {
            pts = 5;
        } else if (d >= 365) {
            pts = 4;
        } else if (d >= 180) {
            pts = 3;
        } else if (d >= 90) {
            pts = 2;
        } else {
            pts = 1;
        }
        return new ScoreComponent("Customer tenure", d + " days", pts, 5, "Days since first order");
    }

    private static ScoreComponent returns(CustomerRiskMetrics m) {
        int r = m.getReturnCancellationCount();
        double pts = r == 0 ? 5 : r <= 2 ? 3 : r <= 5 ? 1 : 0;
        return new ScoreComponent("Return/cancellation", String.valueOf(r), pts, 5, "Void/cancelled invoice lines");
    }

    private static ScoreComponent margin(CustomerRiskMetrics m, CreditRiskEngineConfigDocument cfg) {
        if (m.getCustomerMarginPercent() == null) {
            if (cfg.isRedistributeMarginWeight()) {
                return new ScoreComponent("Customer margin", "n/a", 2.5, 5, "Margin data missing — neutral score");
            }
            return new ScoreComponent("Customer margin", "n/a", 0, 5, "Margin data missing");
        }
        double pct = m.getCustomerMarginPercent();
        double pts = pct >= 20 ? 5 : pct >= 10 ? 3 : pct >= 5 ? 2 : 1;
        return new ScoreComponent("Customer margin", String.format("%.1f", pct), pts, 5, "Average margin");
    }

    static String riskCategory(double score, CreditRiskEngineConfigDocument cfg) {
        if (score >= cfg.getRiskCategoryVeryReliableMin()) {
            return "VERY_RELIABLE";
        }
        if (score >= cfg.getRiskCategoryReliableMin()) {
            return "RELIABLE";
        }
        if (score >= cfg.getRiskCategoryWatchMin()) {
            return "WATCH";
        }
        if (score >= cfg.getRiskCategoryRiskyMin()) {
            return "RISKY";
        }
        return "HIGH_RISK";
    }

    static String paymentBehaviour(CustomerRiskMetrics m) {
        if (m.getOnTimePaymentPercentage() >= 85 && m.getAveragePaymentDelayDays() <= 7 && m.getMaximumOverdueDays() < 45) {
            return "GOOD";
        }
        if (m.getOnTimePaymentPercentage() >= 60 && m.getMaximumOverdueDays() < 90) {
            return "AVERAGE";
        }
        return "POOR";
    }

    static String businessValue(CustomerRiskMetrics m) {
        if (m.getSalesLast90Days() >= 500_000 || m.getLifetimeSales() >= 5_000_000) {
            return "HIGH";
        }
        if (m.getSalesLast90Days() >= 100_000 || m.getLifetimeSales() >= 500_000) {
            return "MEDIUM";
        }
        return "LOW";
    }

    public Double recommendCreditLimit(CustomerRiskMetrics m, CustomerRiskScore score) {
        double monthly = m.getSalesLast90Days() / 3.0;
        if (monthly <= 0) {
            monthly = m.getAverageOrderValue() * Math.max(1, m.getOrderFrequencyPerMonth());
        }
        double factor = switch (score.riskCategory()) {
            case "VERY_RELIABLE" -> 1.5;
            case "RELIABLE" -> 1.2;
            case "WATCH" -> 0.8;
            case "RISKY" -> 0.5;
            default -> 0.25;
        };
        double recommended = Math.round(monthly * factor / 1000.0) * 1000.0;
        return recommended > 0 ? recommended : null;
    }
}
