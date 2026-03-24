package org.example.auth;

import java.util.function.Predicate;

/**
 * Resolves {@link UserPermissions} flags from a validated {@link SessionInfo}.
 * Admins implicitly pass all checks.
 */
public final class SessionPermissions {

    private SessionPermissions() {
    }

    public static boolean has(SessionInfo session, Predicate<UserPermissions> permission) {
        if (session == null) {
            return false;
        }
        if (session.isAdmin()) {
            return true;
        }
        UserPermissions p = session.permissions();
        if (p == null) {
            return false;
        }
        return permission.test(p);
    }

    public static boolean canEditPaymentDate(SessionInfo session) {
        return has(session, UserPermissions::isPaymentDateEdit);
    }

    public static boolean canChangeWhatsappDate(SessionInfo session) {
        return has(session, UserPermissions::isWhatsappDateChange);
    }

    public static boolean canChangeFollowUp(SessionInfo session) {
        return has(session, UserPermissions::isFollowUpChange);
    }

    public static boolean canAccessInvoicePage(SessionInfo session) {
        return has(session, UserPermissions::isInvoicePage);
    }

    public static boolean canAccessSalesVisualization(SessionInfo session) {
        return has(session, UserPermissions::isSalesVisualization);
    }

    public static boolean canAccessDetailsPage(SessionInfo session) {
        return has(session, UserPermissions::isDetailsPage);
    }

    public static boolean canAccessOutstandingPage(SessionInfo session) {
        return has(session, UserPermissions::isOutstandingPage);
    }

    public static boolean canAccessFileUpload(SessionInfo session) {
        return has(session, UserPermissions::isFileUpload);
    }

    public static boolean canDownloadWholeProject(SessionInfo session) {
        return has(session, UserPermissions::isWholeProjectDownload);
    }

    public static boolean canHardDelete(SessionInfo session) {
        return has(session, UserPermissions::isHardDelete);
    }

    public static boolean canAccessRateList(SessionInfo session) {
        return has(session, UserPermissions::isRateListPage);
    }

    public static boolean canAccessCustomerLocations(SessionInfo session) {
        return has(session, UserPermissions::isCustomerLocations);
    }

    /** Customer search autocomplete: invoice, details, or outstanding surfaces. */
    public static boolean canUseCustomerSuggestions(SessionInfo session) {
        if (session == null) {
            return false;
        }
        if (session.isAdmin()) {
            return true;
        }
        UserPermissions p = session.permissions();
        if (p == null) {
            return false;
        }
        return p.isInvoicePage() || p.isDetailsPage() || p.isOutstandingPage();
    }

    /** Phone search on Details or Outstanding flows. */
    public static boolean canAccessDetailsOrOutstanding(SessionInfo session) {
        if (session == null) {
            return false;
        }
        if (session.isAdmin()) {
            return true;
        }
        UserPermissions p = session.permissions();
        if (p == null) {
            return false;
        }
        return p.isDetailsPage() || p.isOutstandingPage();
    }

    /** Category/place edits are allowed from Details or Outstanding pages. */
    public static boolean canEditCustomerMasterFromDetailsOrOutstanding(SessionInfo session) {
        return canAccessDetailsOrOutstanding(session);
    }
}
