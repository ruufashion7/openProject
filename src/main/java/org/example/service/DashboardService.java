package org.example.service;

import org.example.auth.AuthSessionRepository;
import org.example.auth.User;
import org.example.auth.UserRepository;
import org.example.model.DashboardActivityItem;
import org.example.model.DashboardCustomerStats;
import org.example.model.DashboardFileStatus;
import org.example.model.DashboardSummary;
import org.example.model.DashboardSystemInfo;
import org.example.model.DashboardUploadJobInfo;
import org.example.model.DashboardUserRow;
import org.example.model.DashboardWhatsappStats;
import org.example.payment.PaymentDateOverrideRepository;
import org.example.ratelist.RateListEntryRepository;
import org.example.security.RateLimitingService;
import org.example.upload.DetailedSalesInvoicesUpload;
import org.example.upload.DetailedSalesInvoicesUploadRepository;
import org.example.upload.ReceivableAgeingReportUpload;
import org.example.upload.ReceivableAgeingReportUploadRepository;
import org.example.upload.UploadAsyncStateResponse;
import org.example.upload.UploadAuditEntry;
import org.example.upload.UploadAuditEntryRepository;
import org.example.upload.UploadCurrentJobResponse;
import org.example.upload.UploadFileInfo;
import org.example.upload.UploadJobService;
import org.example.upload.UploadLastOutcomeResponse;
import org.example.upload.UploadedExcelFile;
import org.example.upload.UploadedExcelSheet;
import org.example.whatsapp.WhatsappBroadcastBatch;
import org.example.whatsapp.WhatsappBroadcastBatchRepository;
import org.example.whatsapp.WhatsappBroadcastRecipientRepository;
import org.example.whatsapp.WhatsappRecipientStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DashboardService {

    private final UserRepository userRepository;
    private final AuthSessionRepository authSessionRepository;
    private final DetailedSalesInvoicesUploadRepository detailedUploadRepository;
    private final ReceivableAgeingReportUploadRepository receivableUploadRepository;
    private final PaymentDateOverrideRepository paymentDateOverrideRepository;
    private final RateListEntryRepository rateListEntryRepository;
    private final UploadAuditEntryRepository uploadAuditEntryRepository;
    private final WhatsappBroadcastBatchRepository whatsappBatchRepository;
    private final WhatsappBroadcastRecipientRepository whatsappRecipientRepository;
    private final UploadJobService uploadJobService;
    private final RateLimitingService rateLimitingService;
    private final Environment environment;
    private final String applicationName;
    private final String applicationVersion;

    public DashboardService(
            UserRepository userRepository,
            AuthSessionRepository authSessionRepository,
            DetailedSalesInvoicesUploadRepository detailedUploadRepository,
            ReceivableAgeingReportUploadRepository receivableUploadRepository,
            PaymentDateOverrideRepository paymentDateOverrideRepository,
            RateListEntryRepository rateListEntryRepository,
            UploadAuditEntryRepository uploadAuditEntryRepository,
            WhatsappBroadcastBatchRepository whatsappBatchRepository,
            WhatsappBroadcastRecipientRepository whatsappRecipientRepository,
            UploadJobService uploadJobService,
            RateLimitingService rateLimitingService,
            Environment environment,
            @Value("${spring.application.name:openProject}") String applicationName,
            @Value("${info.app.version:1.0-SNAPSHOT}") String applicationVersion) {
        this.userRepository = userRepository;
        this.authSessionRepository = authSessionRepository;
        this.detailedUploadRepository = detailedUploadRepository;
        this.receivableUploadRepository = receivableUploadRepository;
        this.paymentDateOverrideRepository = paymentDateOverrideRepository;
        this.rateListEntryRepository = rateListEntryRepository;
        this.uploadAuditEntryRepository = uploadAuditEntryRepository;
        this.whatsappBatchRepository = whatsappBatchRepository;
        this.whatsappRecipientRepository = whatsappRecipientRepository;
        this.uploadJobService = uploadJobService;
        this.rateLimitingService = rateLimitingService;
        this.environment = environment;
        this.applicationName = applicationName;
        this.applicationVersion = applicationVersion;
    }

    public DashboardSummary getSummary() {
        Instant now = Instant.now();

        List<User> allUsers = userRepository.findAllByOrderByDisplayNameAsc();
        int activeUsers = (int) allUsers.stream().filter(User::isActive).count();
        int inactiveUsers = allUsers.size() - activeUsers;
        int adminUsers = (int) allUsers.stream().filter(User::isAdmin).count();
        int activeSessions = authSessionRepository.findByExpiresAtAfter(now).size();

        List<DashboardUserRow> userRows = allUsers.stream()
                .sorted(Comparator.comparing(User::isActive).reversed()
                        .thenComparing(u -> u.getDisplayName() != null ? u.getDisplayName() : u.getUsername(),
                                String.CASE_INSENSITIVE_ORDER))
                .limit(12)
                .map(u -> new DashboardUserRow(
                        u.getDisplayName(),
                        u.getUsername(),
                        u.isAdmin(),
                        u.isActive(),
                        u.getUpdatedAt()))
                .toList();

        DetailedSalesInvoicesUpload detailed = detailedUploadRepository.findTopByOrderByUploadedAtDesc();
        ReceivableAgeingReportUpload receivable = receivableUploadRepository.findTopByOrderByUploadedAtDesc();
        DashboardFileStatus detailedStatus = toFileStatus(detailed != null ? detailed.file() : null,
                detailed != null ? detailed.uploadedAt() : null);
        DashboardFileStatus receivableStatus = toFileStatus(receivable != null ? receivable.file() : null,
                receivable != null ? receivable.uploadedAt() : null);
        boolean analyticsReady = detailedStatus.present() && receivableStatus.present();

        UploadAsyncStateResponse uploadState = uploadJobService.getAsyncState();
        DashboardUploadJobInfo uploadJob = toUploadJobInfo(uploadState);

        DashboardCustomerStats customers = new DashboardCustomerStats(
                paymentDateOverrideRepository.count(),
                paymentDateOverrideRepository.countActiveCustomers(),
                paymentDateOverrideRepository.countWithPhoneNumber(),
                paymentDateOverrideRepository.countWithCoordinates()
        );

        WhatsappBroadcastBatch latestBatch = whatsappBatchRepository.findFirstByOrderByCreatedAtDesc();
        DashboardWhatsappStats whatsapp = new DashboardWhatsappStats(
                whatsappBatchRepository.count(),
                whatsappRecipientRepository.count(),
                whatsappRecipientRepository.countByStatus(WhatsappRecipientStatus.NOT_SENT),
                whatsappRecipientRepository.countByStatus(WhatsappRecipientStatus.IN_PROGRESS),
                whatsappRecipientRepository.countByStatus(WhatsappRecipientStatus.SENT),
                whatsappRecipientRepository.countByStatus(WhatsappRecipientStatus.FAILED),
                latestBatch != null ? latestBatch.getCreatedAt() : null
        );

        long rateListEntries = rateListEntryRepository.count();
        long uploadAuditEntries = uploadAuditEntryRepository.count();

        List<DashboardActivityItem> recentActivity = uploadAuditEntryRepository
                .findTop100ByOrderByUploadedAtDescIdDesc().stream()
                .limit(8)
                .map(this::toActivityItem)
                .toList();

        DashboardSystemInfo system = new DashboardSystemInfo(
                applicationName,
                applicationVersion,
                formatProfiles(environment.getActiveProfiles()),
                rateLimitingService.rateLimitBackendLabel(),
                rateLimitingService.isRedisBackendActive()
        );

        List<String> alerts = buildAlerts(
                analyticsReady,
                uploadJob,
                detailedStatus,
                receivableStatus,
                customers,
                rateListEntries,
                whatsapp,
                activeUsers,
                adminUsers,
                system
        );

        return new DashboardSummary(
                now,
                analyticsReady,
                system,
                uploadJob,
                customers,
                whatsapp,
                activeUsers,
                inactiveUsers,
                adminUsers,
                activeSessions,
                rateListEntries,
                uploadAuditEntries,
                detailedStatus,
                receivableStatus,
                userRows,
                recentActivity,
                alerts
        );
    }

    private static String formatProfiles(String[] profiles) {
        if (profiles == null || profiles.length == 0) {
            return "default";
        }
        return Arrays.stream(profiles).collect(Collectors.joining(", "));
    }

    private DashboardUploadJobInfo toUploadJobInfo(UploadAsyncStateResponse state) {
        if (state.busy() && state.currentJob() != null) {
            UploadCurrentJobResponse job = state.currentJob();
            return new DashboardUploadJobInfo(
                    true,
                    job.state(),
                    job.phase(),
                    job.message(),
                    job.startedByDisplayName(),
                    job.startedAt(),
                    null,
                    List.of()
            );
        }
        UploadLastOutcomeResponse last = state.lastOutcome();
        if (last != null) {
            List<String> names = last.files() != null
                    ? last.files().stream().map(UploadFileInfo::filename).toList()
                    : List.of();
            return new DashboardUploadJobInfo(
                    false,
                    last.state(),
                    null,
                    last.message(),
                    last.startedByDisplayName(),
                    null,
                    last.completedAt(),
                    names
            );
        }
        return new DashboardUploadJobInfo(false, "idle", null, "No recent upload jobs.", null, null, null, List.of());
    }

    private DashboardFileStatus toFileStatus(UploadedExcelFile file, Instant uploadedAt) {
        if (file == null) {
            return DashboardFileStatus.absent();
        }
        return new DashboardFileStatus(true, file.originalFilename(), uploadedAt, countRows(file));
    }

    private int countRows(UploadedExcelFile file) {
        if (file.sheets() == null) {
            return 0;
        }
        int total = 0;
        for (UploadedExcelSheet sheet : file.sheets()) {
            if (sheet.rows() != null) {
                total += sheet.rows().size();
            }
        }
        return total;
    }

    private DashboardActivityItem toActivityItem(UploadAuditEntry entry) {
        return new DashboardActivityItem(
                entry.action(),
                entry.type(),
                entry.originalFilename(),
                entry.uploadedAt()
        );
    }

    private List<String> buildAlerts(
            boolean analyticsReady,
            DashboardUploadJobInfo uploadJob,
            DashboardFileStatus detailed,
            DashboardFileStatus receivable,
            DashboardCustomerStats customers,
            long rateListEntries,
            DashboardWhatsappStats whatsapp,
            int activeUsers,
            int adminUsers,
            DashboardSystemInfo system
    ) {
        List<String> alerts = new ArrayList<>();
        if (!detailed.present()) {
            alerts.add("Detailed sales invoice file is missing — upload it to enable analytics.");
        }
        if (!receivable.present()) {
            alerts.add("Receivable ageing file is missing — upload it to enable analytics.");
        }
        if (analyticsReady && customers.totalRecords() == 0) {
            alerts.add("Files are uploaded but customer master is empty — re-upload or check phone ingest.");
        }
        if (uploadJob.busy()) {
            alerts.add("Excel upload is running — wait before relying on Payment Dates or Outstanding pages.");
        } else if ("failed".equalsIgnoreCase(uploadJob.state())) {
            alerts.add("Last upload failed: " + uploadJob.message());
        }
        if (rateListEntries == 0) {
            alerts.add("Rate list is empty — add pricing data before sharing quotes.");
        }
        if (activeUsers == 0) {
            alerts.add("No active users — create accounts under Access Control.");
        }
        if (adminUsers == 0) {
            alerts.add("No admin user is assigned — assign admin access for system management.");
        }
        if (customers.withPhone() < customers.activeInUpload() / 2 && customers.activeInUpload() > 10) {
            alerts.add("Many customers lack phone numbers — WhatsApp outreach will be limited.");
        }
        if (whatsapp.failed() > 0) {
            alerts.add(whatsapp.failed() + " WhatsApp recipient(s) marked failed — review in WhatsApp Outreach.");
        }
        if (!system.redisRateLimitActive() && "prod".equalsIgnoreCase(system.activeProfiles())) {
            alerts.add("Rate limits are per-server only (Redis off) — login lockouts may differ across Render instances.");
        }
        return alerts;
    }
}
