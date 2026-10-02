package org.example.creditrisk;

import java.util.ArrayList;
import java.util.List;

/** Aggregated customer metrics used by scoring and order decision. */
public class CustomerRiskMetrics {

    private String customerKey;
    private String customerName;
    private String customerCategory;
    private String phoneNumber;
    private boolean manualHold;
    private String manualHoldReason;
    private Double creditLimitOverride;
    private Double effectiveCreditLimit;
    private String creditLimitSource;
    private Integer paymentTermsDays;

    private double currentOutstanding;
    private double withinAmount;
    private double midAmount;
    private double beyondAmount;
    private double overdueAmount;
    private int overdueInvoiceCount;
    private int maximumOverdueDays;
    private Double creditUtilization;

    private int totalInvoices;
    private int paidInvoices;
    private int partiallyPaidInvoices;
    private int unpaidInvoices;
    private int successfulPaymentCount;
    private double onTimePaymentPercentage;
    private double averagePaymentDelayDays;
    private double maximumPaymentDelayDays;
    private int latePaymentCount;
    private int paymentFailuresLast90Days;
    private int brokenPaymentPromises;
    /** Open cash / per-invoice collect promises (amount still expected). */
    private int openPaymentPromiseCount;
    private double openPaymentPromiseAmount;
    /** Outstanding Due promised/next payment date (customer_master.nextPaymentDate). */
    private String nextPaymentDate;
    private Integer daysSincePaymentAgainstDue;

    private double lifetimeSales;
    private double salesLast7Days;
    private double salesLast30Days;
    private double salesLast90Days;
    private double salesLast180Days;
    private double salesLast365Days;
    private int totalOrders;
    private int ordersLast7Days;
    private int ordersLast30Days;
    private int ordersLast90Days;
    private int ordersLast180Days;
    private double averageOrderValue;
    private double averageOrderValueLast90Days;
    private double largestOrder;
    private double orderFrequencyPerMonth;
    private double orderConsistency; // 0–1
    private int customerTenureDays;
    private String firstOrderDate;
    private String lastOrderDate;
    private double lastOrderAmount;
    private String lastPaymentDate;
    private double lastPaymentAmount;
    private Double customerMarginPercent;
    private int returnCancellationCount;

    private double salesPrevious30Days;
    private double salesPrevious90Days;
    private double outstandingTrendPercent;
    private double onTimeTrendDelta;

    private List<String> dataQualityWarnings = new ArrayList<>();
    private List<String> alerts = new ArrayList<>();

    public String getCustomerKey() {
        return customerKey;
    }

    public void setCustomerKey(String customerKey) {
        this.customerKey = customerKey;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerCategory() {
        return customerCategory;
    }

    public void setCustomerCategory(String customerCategory) {
        this.customerCategory = customerCategory;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public boolean isManualHold() {
        return manualHold;
    }

    public void setManualHold(boolean manualHold) {
        this.manualHold = manualHold;
    }

    public String getManualHoldReason() {
        return manualHoldReason;
    }

    public void setManualHoldReason(String manualHoldReason) {
        this.manualHoldReason = manualHoldReason;
    }

    public Double getCreditLimitOverride() {
        return creditLimitOverride;
    }

    public void setCreditLimitOverride(Double creditLimitOverride) {
        this.creditLimitOverride = creditLimitOverride;
    }

    public Integer getPaymentTermsDays() {
        return paymentTermsDays;
    }

    public void setPaymentTermsDays(Integer paymentTermsDays) {
        this.paymentTermsDays = paymentTermsDays;
    }

    public Double getEffectiveCreditLimit() {
        return effectiveCreditLimit;
    }

    public void setEffectiveCreditLimit(Double effectiveCreditLimit) {
        this.effectiveCreditLimit = effectiveCreditLimit;
    }

    public String getCreditLimitSource() {
        return creditLimitSource;
    }

    public void setCreditLimitSource(String creditLimitSource) {
        this.creditLimitSource = creditLimitSource;
    }

    public double getCurrentOutstanding() {
        return currentOutstanding;
    }

    public void setCurrentOutstanding(double currentOutstanding) {
        this.currentOutstanding = currentOutstanding;
    }

    public double getWithinAmount() {
        return withinAmount;
    }

    public void setWithinAmount(double withinAmount) {
        this.withinAmount = withinAmount;
    }

    public double getMidAmount() {
        return midAmount;
    }

    public void setMidAmount(double midAmount) {
        this.midAmount = midAmount;
    }

    public double getBeyondAmount() {
        return beyondAmount;
    }

    public void setBeyondAmount(double beyondAmount) {
        this.beyondAmount = beyondAmount;
    }

    public double getOverdueAmount() {
        return overdueAmount;
    }

    public void setOverdueAmount(double overdueAmount) {
        this.overdueAmount = overdueAmount;
    }

    public int getOverdueInvoiceCount() {
        return overdueInvoiceCount;
    }

    public void setOverdueInvoiceCount(int overdueInvoiceCount) {
        this.overdueInvoiceCount = overdueInvoiceCount;
    }

    public int getMaximumOverdueDays() {
        return maximumOverdueDays;
    }

    public void setMaximumOverdueDays(int maximumOverdueDays) {
        this.maximumOverdueDays = maximumOverdueDays;
    }

    public Double getCreditUtilization() {
        return creditUtilization;
    }

    public void setCreditUtilization(Double creditUtilization) {
        this.creditUtilization = creditUtilization;
    }

    public int getTotalInvoices() {
        return totalInvoices;
    }

    public void setTotalInvoices(int totalInvoices) {
        this.totalInvoices = totalInvoices;
    }

    public int getPaidInvoices() {
        return paidInvoices;
    }

    public void setPaidInvoices(int paidInvoices) {
        this.paidInvoices = paidInvoices;
    }

    public int getPartiallyPaidInvoices() {
        return partiallyPaidInvoices;
    }

    public void setPartiallyPaidInvoices(int partiallyPaidInvoices) {
        this.partiallyPaidInvoices = partiallyPaidInvoices;
    }

    public int getUnpaidInvoices() {
        return unpaidInvoices;
    }

    public void setUnpaidInvoices(int unpaidInvoices) {
        this.unpaidInvoices = unpaidInvoices;
    }

    public int getSuccessfulPaymentCount() {
        return successfulPaymentCount;
    }

    public void setSuccessfulPaymentCount(int successfulPaymentCount) {
        this.successfulPaymentCount = successfulPaymentCount;
    }

    public double getOnTimePaymentPercentage() {
        return onTimePaymentPercentage;
    }

    public void setOnTimePaymentPercentage(double onTimePaymentPercentage) {
        this.onTimePaymentPercentage = onTimePaymentPercentage;
    }

    public double getAveragePaymentDelayDays() {
        return averagePaymentDelayDays;
    }

    public void setAveragePaymentDelayDays(double averagePaymentDelayDays) {
        this.averagePaymentDelayDays = averagePaymentDelayDays;
    }

    public double getMaximumPaymentDelayDays() {
        return maximumPaymentDelayDays;
    }

    public void setMaximumPaymentDelayDays(double maximumPaymentDelayDays) {
        this.maximumPaymentDelayDays = maximumPaymentDelayDays;
    }

    public int getLatePaymentCount() {
        return latePaymentCount;
    }

    public void setLatePaymentCount(int latePaymentCount) {
        this.latePaymentCount = latePaymentCount;
    }

    public int getPaymentFailuresLast90Days() {
        return paymentFailuresLast90Days;
    }

    public void setPaymentFailuresLast90Days(int paymentFailuresLast90Days) {
        this.paymentFailuresLast90Days = paymentFailuresLast90Days;
    }

    public int getBrokenPaymentPromises() {
        return brokenPaymentPromises;
    }

    public void setBrokenPaymentPromises(int brokenPaymentPromises) {
        this.brokenPaymentPromises = brokenPaymentPromises;
    }

    public int getOpenPaymentPromiseCount() {
        return openPaymentPromiseCount;
    }

    public void setOpenPaymentPromiseCount(int openPaymentPromiseCount) {
        this.openPaymentPromiseCount = openPaymentPromiseCount;
    }

    public double getOpenPaymentPromiseAmount() {
        return openPaymentPromiseAmount;
    }

    public void setOpenPaymentPromiseAmount(double openPaymentPromiseAmount) {
        this.openPaymentPromiseAmount = openPaymentPromiseAmount;
    }

    public String getNextPaymentDate() {
        return nextPaymentDate;
    }

    public void setNextPaymentDate(String nextPaymentDate) {
        this.nextPaymentDate = nextPaymentDate;
    }

    public Integer getDaysSincePaymentAgainstDue() {
        return daysSincePaymentAgainstDue;
    }

    public void setDaysSincePaymentAgainstDue(Integer daysSincePaymentAgainstDue) {
        this.daysSincePaymentAgainstDue = daysSincePaymentAgainstDue;
    }

    public double getLifetimeSales() {
        return lifetimeSales;
    }

    public void setLifetimeSales(double lifetimeSales) {
        this.lifetimeSales = lifetimeSales;
    }

    public double getSalesLast7Days() {
        return salesLast7Days;
    }

    public void setSalesLast7Days(double salesLast7Days) {
        this.salesLast7Days = salesLast7Days;
    }

    public double getSalesLast30Days() {
        return salesLast30Days;
    }

    public void setSalesLast30Days(double salesLast30Days) {
        this.salesLast30Days = salesLast30Days;
    }

    public double getSalesLast90Days() {
        return salesLast90Days;
    }

    public void setSalesLast90Days(double salesLast90Days) {
        this.salesLast90Days = salesLast90Days;
    }

    public double getSalesLast180Days() {
        return salesLast180Days;
    }

    public void setSalesLast180Days(double salesLast180Days) {
        this.salesLast180Days = salesLast180Days;
    }

    public double getSalesLast365Days() {
        return salesLast365Days;
    }

    public void setSalesLast365Days(double salesLast365Days) {
        this.salesLast365Days = salesLast365Days;
    }

    public int getTotalOrders() {
        return totalOrders;
    }

    public void setTotalOrders(int totalOrders) {
        this.totalOrders = totalOrders;
    }

    public int getOrdersLast7Days() {
        return ordersLast7Days;
    }

    public void setOrdersLast7Days(int ordersLast7Days) {
        this.ordersLast7Days = ordersLast7Days;
    }

    public int getOrdersLast30Days() {
        return ordersLast30Days;
    }

    public void setOrdersLast30Days(int ordersLast30Days) {
        this.ordersLast30Days = ordersLast30Days;
    }

    public int getOrdersLast90Days() {
        return ordersLast90Days;
    }

    public void setOrdersLast90Days(int ordersLast90Days) {
        this.ordersLast90Days = ordersLast90Days;
    }

    public int getOrdersLast180Days() {
        return ordersLast180Days;
    }

    public void setOrdersLast180Days(int ordersLast180Days) {
        this.ordersLast180Days = ordersLast180Days;
    }

    public double getAverageOrderValue() {
        return averageOrderValue;
    }

    public void setAverageOrderValue(double averageOrderValue) {
        this.averageOrderValue = averageOrderValue;
    }

    public double getAverageOrderValueLast90Days() {
        return averageOrderValueLast90Days;
    }

    public void setAverageOrderValueLast90Days(double averageOrderValueLast90Days) {
        this.averageOrderValueLast90Days = averageOrderValueLast90Days;
    }

    public double getLargestOrder() {
        return largestOrder;
    }

    public void setLargestOrder(double largestOrder) {
        this.largestOrder = largestOrder;
    }

    public double getOrderFrequencyPerMonth() {
        return orderFrequencyPerMonth;
    }

    public void setOrderFrequencyPerMonth(double orderFrequencyPerMonth) {
        this.orderFrequencyPerMonth = orderFrequencyPerMonth;
    }

    public double getOrderConsistency() {
        return orderConsistency;
    }

    public void setOrderConsistency(double orderConsistency) {
        this.orderConsistency = orderConsistency;
    }

    public int getCustomerTenureDays() {
        return customerTenureDays;
    }

    public void setCustomerTenureDays(int customerTenureDays) {
        this.customerTenureDays = customerTenureDays;
    }

    public String getFirstOrderDate() {
        return firstOrderDate;
    }

    public void setFirstOrderDate(String firstOrderDate) {
        this.firstOrderDate = firstOrderDate;
    }

    public String getLastOrderDate() {
        return lastOrderDate;
    }

    public void setLastOrderDate(String lastOrderDate) {
        this.lastOrderDate = lastOrderDate;
    }

    public double getLastOrderAmount() {
        return lastOrderAmount;
    }

    public void setLastOrderAmount(double lastOrderAmount) {
        this.lastOrderAmount = lastOrderAmount;
    }

    public String getLastPaymentDate() {
        return lastPaymentDate;
    }

    public void setLastPaymentDate(String lastPaymentDate) {
        this.lastPaymentDate = lastPaymentDate;
    }

    public double getLastPaymentAmount() {
        return lastPaymentAmount;
    }

    public void setLastPaymentAmount(double lastPaymentAmount) {
        this.lastPaymentAmount = lastPaymentAmount;
    }

    public Double getCustomerMarginPercent() {
        return customerMarginPercent;
    }

    public void setCustomerMarginPercent(Double customerMarginPercent) {
        this.customerMarginPercent = customerMarginPercent;
    }

    public int getReturnCancellationCount() {
        return returnCancellationCount;
    }

    public void setReturnCancellationCount(int returnCancellationCount) {
        this.returnCancellationCount = returnCancellationCount;
    }

    public double getSalesPrevious30Days() {
        return salesPrevious30Days;
    }

    public void setSalesPrevious30Days(double salesPrevious30Days) {
        this.salesPrevious30Days = salesPrevious30Days;
    }

    public double getSalesPrevious90Days() {
        return salesPrevious90Days;
    }

    public void setSalesPrevious90Days(double salesPrevious90Days) {
        this.salesPrevious90Days = salesPrevious90Days;
    }

    public double getOutstandingTrendPercent() {
        return outstandingTrendPercent;
    }

    public void setOutstandingTrendPercent(double outstandingTrendPercent) {
        this.outstandingTrendPercent = outstandingTrendPercent;
    }

    public double getOnTimeTrendDelta() {
        return onTimeTrendDelta;
    }

    public void setOnTimeTrendDelta(double onTimeTrendDelta) {
        this.onTimeTrendDelta = onTimeTrendDelta;
    }

    public List<String> getDataQualityWarnings() {
        return dataQualityWarnings;
    }

    public void setDataQualityWarnings(List<String> dataQualityWarnings) {
        this.dataQualityWarnings = dataQualityWarnings != null ? dataQualityWarnings : new ArrayList<>();
    }

    public List<String> getAlerts() {
        return alerts;
    }

    public void setAlerts(List<String> alerts) {
        this.alerts = alerts != null ? alerts : new ArrayList<>();
    }
}
