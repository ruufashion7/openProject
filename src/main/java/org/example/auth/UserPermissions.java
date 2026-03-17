package org.example.auth;

public class UserPermissions {
    private boolean fileUpload;
    private boolean hardDelete;
    private boolean invoicePage;
    private boolean detailsPage;
    private boolean wholeProjectDownload;
    private boolean outstandingPage;
    private boolean paymentDateEdit;
    private boolean whatsappDateChange;
    private boolean followUpChange;
    private boolean rateListPage;
    private boolean salesVisualization;
    private boolean customerLocations;

    public UserPermissions() {
        // Default all permissions to false
    }

    public UserPermissions(boolean fileUpload, boolean hardDelete, boolean invoicePage,
                          boolean detailsPage, boolean wholeProjectDownload, boolean outstandingPage,
                          boolean paymentDateEdit, boolean whatsappDateChange, boolean followUpChange,
                          boolean rateListPage, boolean salesVisualization, boolean customerLocations) {
        this.fileUpload = fileUpload;
        this.hardDelete = hardDelete;
        this.invoicePage = invoicePage;
        this.detailsPage = detailsPage;
        this.wholeProjectDownload = wholeProjectDownload;
        this.outstandingPage = outstandingPage;
        this.paymentDateEdit = paymentDateEdit;
        this.whatsappDateChange = whatsappDateChange;
        this.followUpChange = followUpChange;
        this.rateListPage = rateListPage;
        this.salesVisualization = salesVisualization;
        this.customerLocations = customerLocations;
    }

    // Getters and Setters
    public boolean isFileUpload() {
        return fileUpload;
    }

    public void setFileUpload(boolean fileUpload) {
        this.fileUpload = fileUpload;
    }

    public boolean isHardDelete() {
        return hardDelete;
    }

    public void setHardDelete(boolean hardDelete) {
        this.hardDelete = hardDelete;
    }

    public boolean isInvoicePage() {
        return invoicePage;
    }

    public void setInvoicePage(boolean invoicePage) {
        this.invoicePage = invoicePage;
    }

    public boolean isDetailsPage() {
        return detailsPage;
    }

    public void setDetailsPage(boolean detailsPage) {
        this.detailsPage = detailsPage;
    }

    public boolean isWholeProjectDownload() {
        return wholeProjectDownload;
    }

    public void setWholeProjectDownload(boolean wholeProjectDownload) {
        this.wholeProjectDownload = wholeProjectDownload;
    }

    public boolean isOutstandingPage() {
        return outstandingPage;
    }

    public void setOutstandingPage(boolean outstandingPage) {
        this.outstandingPage = outstandingPage;
    }

    public boolean isPaymentDateEdit() {
        return paymentDateEdit;
    }

    public void setPaymentDateEdit(boolean paymentDateEdit) {
        this.paymentDateEdit = paymentDateEdit;
    }

    public boolean isWhatsappDateChange() {
        return whatsappDateChange;
    }

    public void setWhatsappDateChange(boolean whatsappDateChange) {
        this.whatsappDateChange = whatsappDateChange;
    }

    public boolean isFollowUpChange() {
        return followUpChange;
    }

    public void setFollowUpChange(boolean followUpChange) {
        this.followUpChange = followUpChange;
    }

    public boolean isRateListPage() {
        return rateListPage;
    }

    public void setRateListPage(boolean rateListPage) {
        this.rateListPage = rateListPage;
    }

    public boolean isSalesVisualization() {
        return salesVisualization;
    }

    public void setSalesVisualization(boolean salesVisualization) {
        this.salesVisualization = salesVisualization;
    }

    public boolean isCustomerLocations() {
        return customerLocations;
    }

    public void setCustomerLocations(boolean customerLocations) {
        this.customerLocations = customerLocations;
    }
}

