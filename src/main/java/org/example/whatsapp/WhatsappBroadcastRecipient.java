package org.example.whatsapp;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "whatsapp_broadcast_recipients")
public class WhatsappBroadcastRecipient {

    @Id
    private String id;
    private String batchId;
    private String customerKey;
    private String displayName;
    /** Digits only, with country code (e.g. 91...) */
    private String phoneDigits;
    private String renderedMessage;
    private WhatsappRecipientStatus status;
    private String failureReason;
    private Instant openedAt;
    private Instant sentAt;
    private Instant updatedAt;
    private String updatedByUserId;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getCustomerKey() {
        return customerKey;
    }

    public void setCustomerKey(String customerKey) {
        this.customerKey = customerKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPhoneDigits() {
        return phoneDigits;
    }

    public void setPhoneDigits(String phoneDigits) {
        this.phoneDigits = phoneDigits;
    }

    public String getRenderedMessage() {
        return renderedMessage;
    }

    public void setRenderedMessage(String renderedMessage) {
        this.renderedMessage = renderedMessage;
    }

    public WhatsappRecipientStatus getStatus() {
        return status;
    }

    public void setStatus(WhatsappRecipientStatus status) {
        this.status = status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(Instant openedAt) {
        this.openedAt = openedAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedByUserId() {
        return updatedByUserId;
    }

    public void setUpdatedByUserId(String updatedByUserId) {
        this.updatedByUserId = updatedByUserId;
    }
}
