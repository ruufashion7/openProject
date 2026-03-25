package org.example.api;

import org.example.auth.AuthSessionService;
import org.example.auth.SessionInfo;
import org.example.auth.SessionPermissions;
import org.example.payment.PaymentDateOverride;
import org.example.payment.PaymentDateOverrideRepository;
import org.example.upload.DetailedSalesInvoicesUpload;
import org.example.upload.DetailedSalesInvoicesUploadRepository;
import org.example.upload.ReceivableAgeingReportUpload;
import org.example.upload.ReceivableAgeingReportUploadRepository;
import org.example.upload.UploadedExcelFile;
import org.example.upload.UploadedExcelSheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {
    private static final Logger logger = LoggerFactory.getLogger(AnalyticsController.class);
    private static final List<DateTimeFormatter> INVOICE_DATE_TIME_FORMATTERS = List.of(
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-yyyy hh:mm a").toFormatter(Locale.ENGLISH),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-yyyy HH:mm").toFormatter(Locale.ENGLISH),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-yyyy HH:mm:ss").toFormatter(Locale.ENGLISH)
    );
    private static final List<DateTimeFormatter> INVOICE_DATE_FORMATTERS = List.of(
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-yyyy").toFormatter(Locale.ENGLISH),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd/MM/yyyy").toFormatter(Locale.ENGLISH),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("yyyy-MM-dd").toFormatter(Locale.ENGLISH)
    );
    private final AuthSessionService authSessionService;
    private final DetailedSalesInvoicesUploadRepository detailedSalesInvoicesUploadRepository;
    private final ReceivableAgeingReportUploadRepository receivableAgeingReportUploadRepository;
    private final PaymentDateOverrideRepository paymentDateOverrideRepository;

    public AnalyticsController(AuthSessionService authSessionService,
                               DetailedSalesInvoicesUploadRepository detailedSalesInvoicesUploadRepository,
                               ReceivableAgeingReportUploadRepository receivableAgeingReportUploadRepository,
                               PaymentDateOverrideRepository paymentDateOverrideRepository) {
        this.authSessionService = authSessionService;
        this.detailedSalesInvoicesUploadRepository = detailedSalesInvoicesUploadRepository;
        this.receivableAgeingReportUploadRepository = receivableAgeingReportUploadRepository;
        this.paymentDateOverrideRepository = paymentDateOverrideRepository;
    }

    @PostMapping("/customers")
    public ResponseEntity<List<String>> customerSuggestions(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody org.example.api.request.SuggestionRequest request
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canUseCustomerSuggestions(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // SECURITY: Extract from POST body instead of URL query parameters
        String query = request != null ? request.query() : null;
        int limit = request != null && request.limit() != null ? request.limit() : 20;

        // SECURITY: Validate query parameters
        if (query == null || query.trim().length() < 3) {
            return ResponseEntity.ok(List.of());
        }

        // SECURITY: Sanitize and limit query length
        query = query.trim();
        if (query.length() > 100) {
            query = query.substring(0, 100);
        }

        // SECURITY: Validate limit to prevent DoS
        if (limit < 1 || limit > 100) {
            limit = 20;
        }

        DetailedSalesInvoicesUpload latest = detailedSalesInvoicesUploadRepository.findTopByOrderByUploadedAtDesc();
        if (latest == null) {
            return ResponseEntity.ok(List.of());
        }

        String q = query.trim().toLowerCase(Locale.ROOT);
        Set<String> results = new LinkedHashSet<>();
        UploadedExcelFile file = latest.file();

        for (UploadedExcelSheet sheet : file.sheets()) {
            List<String> customerHeaders = sheet.headers().stream()
                    .filter(this::isCustomerHeader)
                    .toList();
            if (customerHeaders.isEmpty()) {
                continue;
            }
            for (Map<String, String> row : sheet.rows()) {
                for (String header : customerHeaders) {
                    String value = row.get(header);
                    if (value == null || value.isBlank()) {
                        continue;
                    }
                    String normalized = value.trim();
                    if (normalized.toLowerCase(Locale.ROOT).contains(q)) {
                        results.add(normalized);
                        if (results.size() >= limit) {
                            return ResponseEntity.ok(new ArrayList<>(results));
                        }
                    }
                }
            }
        }

        return ResponseEntity.ok(new ArrayList<>(results));
    }

    @PostMapping("/phone-suggestions")
    public ResponseEntity<List<String>> phoneSuggestions(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody org.example.api.request.SuggestionRequest request
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessDetailsOrOutstanding(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // SECURITY: Extract from POST body instead of URL query parameters
        String query = request != null ? request.query() : null;
        int limit = request != null && request.limit() != null ? request.limit() : 20;
        // SECURITY: Validate query parameters
        if (query == null || query.trim().length() < 3) {
            return ResponseEntity.ok(List.of());
        }
        
        // SECURITY: Sanitize and limit query length
        query = query.trim();
        if (query.length() > 100) {
            query = query.substring(0, 100);
        }
        
        // SECURITY: Validate limit
        if (limit < 1 || limit > 100) {
            limit = 20;
        }

        String q = query.trim().toLowerCase(Locale.ROOT);
        Set<String> results = new LinkedHashSet<>();
        
        List<PaymentDateOverride> allOverrides = paymentDateOverrideRepository.findAll();
        for (PaymentDateOverride override : allOverrides) {
            if (override.phoneNumber() != null && !override.phoneNumber().isBlank()) {
                String phone = override.phoneNumber().trim();
                if (phone.toLowerCase(Locale.ROOT).contains(q)) {
                    results.add(phone);
                    if (results.size() >= limit) {
                        return ResponseEntity.ok(new ArrayList<>(results));
                    }
                }
            }
        }

        return ResponseEntity.ok(new ArrayList<>(results));
    }

    @PostMapping("/voucher-suggestions")
    public ResponseEntity<List<String>> voucherSuggestions(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody org.example.api.request.SuggestionRequest request
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessInvoicePage(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // SECURITY: Extract from POST body instead of URL query parameters
        String query = request != null ? request.query() : null;
        int limit = request != null && request.limit() != null ? request.limit() : 20;
        // SECURITY: Validate query parameters
        if (query != null) {
            query = query.trim();
            if (query.length() > 100) {
                query = query.substring(0, 100);
            }
        }
        
        // SECURITY: Validate limit
        if (limit < 1 || limit > 100) {
            limit = 20;
        }

        if (query == null || query.trim().length() < 3) {
            return ResponseEntity.ok(List.of());
        }

        DetailedSalesInvoicesUpload latest = detailedSalesInvoicesUploadRepository.findTopByOrderByUploadedAtDesc();
        if (latest == null) {
            return ResponseEntity.ok(List.of());
        }

        String q = query.trim().toLowerCase(Locale.ROOT);
        Set<String> results = new LinkedHashSet<>();
        UploadedExcelFile file = latest.file();

        for (UploadedExcelSheet sheet : file.sheets()) {
            List<String> voucherHeaders = sheet.headers().stream()
                    .filter(this::isVoucherHeader)
                    .toList();
            if (voucherHeaders.isEmpty()) {
                continue;
            }
            for (Map<String, String> row : sheet.rows()) {
                for (String header : voucherHeaders) {
                    String value = row.get(header);
                    if (value == null || value.isBlank()) {
                        continue;
                    }
                    String normalized = value.trim();
                    if (normalized.toLowerCase(Locale.ROOT).contains(q)) {
                        results.add(normalized);
                        if (results.size() >= limit) {
                            return ResponseEntity.ok(new ArrayList<>(results));
                        }
                    }
                }
            }
        }

        return ResponseEntity.ok(new ArrayList<>(results));
    }

    @PostMapping("/customer-summary")
    public ResponseEntity<CustomerSummaryResponse> customerSummary(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody org.example.api.request.CustomerSearchRequest request
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessDetailsOrOutstanding(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // SECURITY: Extract from POST body instead of URL query parameters
        String customer = request != null ? request.customer() : null;
        String phone = request != null ? request.phone() : null;
        
        // SECURITY: Validate that at least one parameter is provided
        if ((customer == null || customer.isBlank()) && (phone == null || phone.isBlank())) {
            return ResponseEntity.badRequest().build();
        }

        ReceivableAgeingReportUpload latest = receivableAgeingReportUploadRepository.findTopByOrderByUploadedAtDesc();
        
        // If phone is provided, search directly in the data by phone number
        // Also check if customer parameter is actually a phone number (numeric)
        Set<String> targetCustomers = new HashSet<>();
        String searchPhone = null;
        String resolvedCustomer = customer;
        
        // Determine if we're searching by phone number
        if (phone != null && !phone.isBlank()) {
            searchPhone = phone.trim();
        } else if (customer != null && !customer.isBlank()) {
            // Check if customer parameter is actually a phone number (all digits, length >= 10)
            String customerTrimmed = customer.trim();
            if (customerTrimmed.matches("\\d{10,}")) {
                // It's a phone number, treat it as such
                searchPhone = customerTrimmed;
                logger.info("Detected phone number in customer parameter: {}", searchPhone);
            } else {
                // It's a customer name
                targetCustomers.add(normalizeCustomer(customerTrimmed));
                resolvedCustomer = customerTrimmed;
            }
        } else {
            return ResponseEntity.badRequest().build();
        }
        
        // If searching by phone, try to find customer from PaymentDateOverride first
        if (searchPhone != null) {
            String customerByPhone = findCustomerByPhone(searchPhone);
            if (customerByPhone != null) {
                targetCustomers.add(normalizeCustomer(customerByPhone));
                resolvedCustomer = customerByPhone;
                logger.info("Found customer '{}' for phone '{}' from PaymentDateOverride", customerByPhone, searchPhone);
            } else {
                logger.info("No customer found in PaymentDateOverride for phone '{}', will search data directly", searchPhone);
            }
        }
        
        // Get payment date, WhatsApp status, and follow-up flag from customer_master
        // Use the first resolved customer name if available
        String customerKey = resolvedCustomer != null ? normalizeCustomer(resolvedCustomer) : "";
        PaymentDateOverride paymentDateOverride = customerKey.isBlank() ? null 
                : paymentDateOverrideRepository.findFirstByCustomerKeyOrderByIdAsc(customerKey).orElse(null);
        String nextPaymentDate = paymentDateOverride != null ? paymentDateOverride.nextPaymentDate() : null;
        String whatsAppStatus = paymentDateOverride != null ? paymentDateOverride.whatsAppStatus() : null;
        String customerCategory = paymentDateOverride != null ? paymentDateOverride.customerCategory() : null;
        Boolean needsFollowUp = paymentDateOverride != null ? paymentDateOverride.needsFollowUp() : false;
        
        if (latest == null) {
            // If searching by phone and no upload data, try to get customer name from PaymentDateOverride
            String customerNameForResponse = resolvedCustomer;
            if (searchPhone != null && resolvedCustomer != null && resolvedCustomer.matches("\\d{10,}")) {
                // resolvedCustomer is a phone number, try to find actual customer name
                String customerByPhone = findCustomerByPhone(searchPhone);
                if (customerByPhone != null) {
                    customerNameForResponse = customerByPhone;
                } else {
                    // No customer name found, use empty string instead of phone number
                    customerNameForResponse = "";
                }
            }
            String address = paymentDateOverride != null ? paymentDateOverride.address() : null;
            Double latitude = paymentDateOverride != null ? paymentDateOverride.latitude() : null;
            Double longitude = paymentDateOverride != null ? paymentDateOverride.longitude() : null;
            String place = paymentDateOverride != null ? paymentDateOverride.place() : null;
            return ResponseEntity.ok(new CustomerSummaryResponse(
                    customerNameForResponse != null ? customerNameForResponse : "",
                    false,
                    searchPhone,
                    0.0,
                    false,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    nextPaymentDate,
                    whatsAppStatus,
                    customerCategory,
                    needsFollowUp,
                    address,
                    latitude,
                    longitude,
                    place
            ));
        }

        UploadedExcelFile file = latest.file();
        double totalAmount = 0.0;
        double withinAmount = 0.0;
        double midAmount = 0.0;
        double beyondAmount = 0.0;
        double unknownAmount = 0.0;
        Double totalColumnAmount = null;
        String phoneNumber = searchPhone != null ? searchPhone : (resolvedCustomer != null ? findCustomerPhone(resolvedCustomer) : null);
        boolean found = false;
        // Initialize foundCustomerName: if searching by phone, start with null; otherwise use resolvedCustomer
        // This ensures we only return actual customer names, not phone numbers
        String foundCustomerName = (searchPhone != null) ? null : resolvedCustomer;

        for (UploadedExcelSheet sheet : file.sheets()) {
            List<String> customerHeaders = sheet.headers().stream()
                    .filter(this::isCustomerHeader)
                    .toList();
            if (customerHeaders.isEmpty()) {
                continue;
            }

            List<String> phoneHeaders = sheet.headers().stream()
                    .filter(this::isPhoneHeader)
                    .toList();

            List<String> amountHeaders = sheet.headers().stream()
                    .filter(this::isAmountHeader)
                    .toList();

            if (searchPhone != null && phoneHeaders.isEmpty()) {
                logger.debug("Sheet '{}' has no phone headers, skipping phone search", sheet.name());
            }
            if (searchPhone != null && !phoneHeaders.isEmpty()) {
                logger.debug("Sheet '{}' has {} phone headers: {}", sheet.name(), phoneHeaders.size(), phoneHeaders);
            }

            for (Map<String, String> row : sheet.rows()) {
                boolean rowMatches = false;
                String matchedCustomerName = null;
                
                // If searching by phone, check phone columns first
                if (searchPhone != null) {
                    for (String phoneHeader : phoneHeaders) {
                        String phoneValue = row.get(phoneHeader);
                        if (phoneValue != null && !phoneValue.isBlank()) {
                            String phoneValueTrimmed = phoneValue.trim();
                            // Remove common phone number formatting characters for comparison
                            String normalizedPhoneValue = phoneValueTrimmed.replaceAll("[^0-9]", "");
                            String normalizedSearchPhone = searchPhone.replaceAll("[^0-9]", "");
                            
                            if (normalizedPhoneValue.equals(normalizedSearchPhone) 
                                    || normalizedPhoneValue.contains(normalizedSearchPhone) 
                                    || normalizedSearchPhone.contains(normalizedPhoneValue)
                                    || phoneValueTrimmed.equals(searchPhone)
                                    || phoneValueTrimmed.contains(searchPhone) 
                                    || searchPhone.contains(phoneValueTrimmed)) {
                                // Phone matches, get customer name from this row
                                Optional<String> customerValue = firstCustomerValue(row, customerHeaders);
                                if (customerValue.isPresent()) {
                                    String customerName = customerValue.get();
                                    if (customerName != null && !customerName.isBlank()) {
                                        targetCustomers.add(normalizeCustomer(customerName));
                                        matchedCustomerName = customerName;
                                        rowMatches = true;
                                        if (foundCustomerName == null || foundCustomerName.isEmpty()) {
                                            foundCustomerName = customerName;
                                        }
                                        logger.debug("Found matching phone '{}' -> customer '{}' in sheet '{}'", 
                                                phoneValueTrimmed, customerName, sheet.name());
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
                
                // Also check if customer name matches any in our target set
                if (!rowMatches) {
                    for (String targetCustomer : targetCustomers) {
                        Optional<String> matchedCustomer = findCustomerValue(row, customerHeaders, targetCustomer);
                        if (matchedCustomer.isPresent()) {
                            rowMatches = true;
                            matchedCustomerName = matchedCustomer.get();
                            if (foundCustomerName == null || foundCustomerName.isEmpty()) {
                                foundCustomerName = matchedCustomerName;
                            }
                            break;
                        }
                    }
                }
                
                if (!rowMatches) {
                    continue;
                }
                
                found = true;
                for (String amountHeader : amountHeaders) {
                    double amount = parseAmount(row.get(amountHeader));
                    if (amount == 0) {
                        continue;
                    }
                    AmountBucket bucket = classifyAmountHeader(amountHeader);
                    if (bucket == AmountBucket.TOTAL) {
                        totalColumnAmount = totalColumnAmount == null ? amount : totalColumnAmount + amount;
                    } else if (bucket == AmountBucket.WITHIN) {
                        withinAmount += amount;
                    } else if (bucket == AmountBucket.MID) {
                        midAmount += amount;
                    } else if (bucket == AmountBucket.BEYOND) {
                        beyondAmount += amount;
                    } else {
                        totalAmount += amount;
                        unknownAmount += amount;
                    }
                }
            }
        }

        if (totalColumnAmount != null) {
            totalAmount = totalColumnAmount;
        } else if (withinAmount > 0 || midAmount > 0 || beyondAmount > 0) {
            totalAmount = withinAmount + midAmount + beyondAmount + unknownAmount;
        }

        boolean within45Days = withinAmount > 0 && midAmount == 0 && beyondAmount == 0 && unknownAmount == 0;
        
        // Update customer key and payment date override if we found a different customer name
        if (found && foundCustomerName != null && !foundCustomerName.equals(resolvedCustomer)) {
            customerKey = normalizeCustomer(foundCustomerName);
            paymentDateOverride = paymentDateOverrideRepository.findFirstByCustomerKeyOrderByIdAsc(customerKey).orElse(null);
            if (paymentDateOverride != null) {
                nextPaymentDate = paymentDateOverride.nextPaymentDate();
                whatsAppStatus = paymentDateOverride.whatsAppStatus();
                needsFollowUp = paymentDateOverride.needsFollowUp() != null ? paymentDateOverride.needsFollowUp() : false;
                customerCategory = paymentDateOverride.customerCategory();
            } else {
                nextPaymentDate = null;
                whatsAppStatus = null;
                customerCategory = null;
                needsFollowUp = false;
            }
        }
        
        // If searching by phone and we found data but no customer name yet, try to get it from PaymentDateOverride
        if (searchPhone != null && found && (foundCustomerName == null || foundCustomerName.isEmpty())) {
            // Try to find customer name from PaymentDateOverride using any of the target customers
            for (String targetCustomer : targetCustomers) {
                PaymentDateOverride override = paymentDateOverrideRepository.findFirstByCustomerKeyOrderByIdAsc(targetCustomer).orElse(null);
                if (override != null && override.customerName() != null && !override.customerName().isBlank()) {
                    foundCustomerName = override.customerName();
                    break;
                }
            }
        }
        
        // Final fallback: if still no customer name found but we have resolvedCustomer (from PaymentDateOverride), use it
        // But only if it's not a phone number (not all digits)
        if ((foundCustomerName == null || foundCustomerName.isEmpty()) && resolvedCustomer != null) {
            if (!resolvedCustomer.matches("\\d{10,}")) {
                // resolvedCustomer is not a phone number, use it
                foundCustomerName = resolvedCustomer;
            }
        }
        
        // Log summary for debugging
        logger.info("Customer summary search - phone: {}, customer: {}, found: {}, foundCustomerName: {}, targetCustomers: {}, totalAmount: {}", 
                searchPhone, resolvedCustomer, found, foundCustomerName, targetCustomers.size(), totalAmount);
        
        String address = paymentDateOverride != null ? paymentDateOverride.address() : null;
        Double latitude = paymentDateOverride != null ? paymentDateOverride.latitude() : null;
        Double longitude = paymentDateOverride != null ? paymentDateOverride.longitude() : null;
        String place = paymentDateOverride != null ? paymentDateOverride.place() : null;
        return ResponseEntity.ok(new CustomerSummaryResponse(
                foundCustomerName != null && !foundCustomerName.isEmpty() ? foundCustomerName : (resolvedCustomer != null && !resolvedCustomer.matches("\\d{10,}") ? resolvedCustomer : ""),
                found,
                phoneNumber,
                totalAmount,
                within45Days,
                withinAmount,
                midAmount,
                beyondAmount,
                unknownAmount,
                nextPaymentDate,
                whatsAppStatus,
                customerCategory,
                needsFollowUp,
                address,
                latitude,
                longitude,
                place
        ));
    }

    @PostMapping("/customer-ledger")
    public ResponseEntity<List<CustomerLedgerEntry>> customerLedger(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody org.example.api.request.CustomerSearchRequest request
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessDetailsOrOutstanding(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // SECURITY: Extract from POST body instead of URL query parameters
        String customer = request != null ? request.customer() : null;
        String phone = request != null ? request.phone() : null;

        DetailedSalesInvoicesUpload latest = detailedSalesInvoicesUploadRepository.findTopByOrderByUploadedAtDesc();
        if (latest == null) {
            return ResponseEntity.ok(List.of());
        }

        // If phone is provided, search directly in the data by phone number
        // Also check if customer parameter is actually a phone number (numeric)
        Set<String> targetCustomers = new HashSet<>();
        String searchPhone = null;
        
        // Determine if we're searching by phone number
        if (phone != null && !phone.isBlank()) {
            searchPhone = phone.trim();
        } else if (customer != null && !customer.isBlank()) {
            // Check if customer parameter is actually a phone number (all digits, length >= 10)
            String customerTrimmed = customer.trim();
            if (customerTrimmed.matches("\\d{10,}")) {
                // It's a phone number, treat it as such
                searchPhone = customerTrimmed;
                logger.info("Detected phone number in customer parameter: {}", searchPhone);
            } else {
                // It's a customer name
                targetCustomers.add(normalizeCustomer(customerTrimmed));
            }
        } else {
            return ResponseEntity.badRequest().build();
        }
        
        // If searching by phone, try to find customer from PaymentDateOverride first
        if (searchPhone != null) {
            String customerByPhone = findCustomerByPhone(searchPhone);
            if (customerByPhone != null) {
                targetCustomers.add(normalizeCustomer(customerByPhone));
                logger.info("Found customer '{}' for phone '{}' from PaymentDateOverride", customerByPhone, searchPhone);
            } else {
                logger.info("No customer found in PaymentDateOverride for phone '{}', will search data directly", searchPhone);
            }
        }

        UploadedExcelFile file = latest.file();
        List<CustomerLedgerEntry> entries = new java.util.ArrayList<>();

        for (UploadedExcelSheet sheet : file.sheets()) {
            List<String> customerHeaders = sheet.headers().stream()
                    .filter(this::isCustomerHeader)
                    .toList();
            if (customerHeaders.isEmpty()) {
                continue;
            }

            List<String> phoneHeaders = sheet.headers().stream()
                    .filter(this::isPhoneHeader)
                    .toList();

            List<String> invoiceHeaders = sheet.headers().stream()
                    .filter(this::isInvoiceDateHeader)
                    .toList();
            List<String> voucherHeaders = sheet.headers().stream()
                    .filter(this::isVoucherHeader)
                    .toList();
            List<String> receivedHeaders = sheet.headers().stream()
                    .filter(this::isReceivedHeader)
                    .toList();
            List<String> dueHeaders = sheet.headers().stream()
                    .filter(this::isCurrentDueHeader)
                    .toList();
            if (!receivedHeaders.isEmpty() || !dueHeaders.isEmpty()) {
                logger.info("Ledger headers detected. sheet={}, received={}, due={}",
                        sheet.name(), receivedHeaders, dueHeaders);
            }

            for (Map<String, String> row : sheet.rows()) {
                boolean rowMatches = false;
                
                // If searching by phone, check phone columns first
                if (searchPhone != null) {
                    for (String phoneHeader : phoneHeaders) {
                        String phoneValue = row.get(phoneHeader);
                        if (phoneValue != null && !phoneValue.isBlank()) {
                            String phoneValueTrimmed = phoneValue.trim();
                            // Remove common phone number formatting characters for comparison
                            String normalizedPhoneValue = phoneValueTrimmed.replaceAll("[^0-9]", "");
                            String normalizedSearchPhone = searchPhone.replaceAll("[^0-9]", "");
                            
                            if (normalizedPhoneValue.equals(normalizedSearchPhone) 
                                    || normalizedPhoneValue.contains(normalizedSearchPhone) 
                                    || normalizedSearchPhone.contains(normalizedPhoneValue)
                                    || phoneValueTrimmed.equals(searchPhone)
                                    || phoneValueTrimmed.contains(searchPhone) 
                                    || searchPhone.contains(phoneValueTrimmed)) {
                                // Phone matches, get customer name from this row and add to target set
                                Optional<String> customerValue = firstCustomerValue(row, customerHeaders);
                                if (customerValue.isPresent()) {
                                    String customerName = customerValue.get();
                                    if (customerName != null && !customerName.isBlank()) {
                                        targetCustomers.add(normalizeCustomer(customerName));
                                        rowMatches = true;
                                        logger.debug("Found matching phone '{}' -> customer '{}' in sheet '{}'", 
                                                phoneValueTrimmed, customerName, sheet.name());
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
                
                // Also check if customer name matches any in our target set
                if (!rowMatches) {
                    for (String targetCustomer : targetCustomers) {
                        Optional<String> matchedCustomer = findCustomerValue(row, customerHeaders, targetCustomer);
                        if (matchedCustomer.isPresent()) {
                            rowMatches = true;
                            break;
                        }
                    }
                }
                
                if (!rowMatches) {
                    continue;
                }

                String invoiceDate = firstNonBlank(row, invoiceHeaders);
                String voucherNo = firstNonBlank(row, voucherHeaders);
                double receivedAmount = sumAmounts(row, receivedHeaders);
                double currentDue = sumAmounts(row, dueHeaders);
                // Only compute ageing days when there's an amount due
                Integer ageingDays = (currentDue > 0.01) ? computeAgeingDays(invoiceDate) : null;

                if (invoiceDate == null && voucherNo == null && receivedAmount == 0 && currentDue == 0) {
                    continue;
                }

                entries.add(new CustomerLedgerEntry(invoiceDate, voucherNo, receivedAmount, currentDue, ageingDays));
            }
        }

        return ResponseEntity.ok(entries);
    }

    @GetMapping("/payment-dates")
    public ResponseEntity<List<PaymentDateCustomerCard>> paymentDates(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessOutstandingPage(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        ReceivableAgeingReportUpload latest = receivableAgeingReportUploadRepository.findTopByOrderByUploadedAtDesc();
        if (latest == null) {
            return ResponseEntity.ok(List.of());
        }

        // Load all customer_master records for fuzzy matching
        List<PaymentDateOverride> allOverridesList = paymentDateOverrideRepository.findAll();
        Map<String, PaymentDateOverride> paymentDateOverrides = allOverridesList.stream()
                .collect(Collectors.toMap(PaymentDateOverride::customerKey, override -> override, (a, b) -> a));

        Map<String, String> lastOrderDates = buildLastOrderDateMap();

        Map<String, PaymentDateAggregate> aggregates = new java.util.LinkedHashMap<>();
        UploadedExcelFile file = latest.file();
        
        // Track which customers are found in the Excel file
        Set<String> customersInExcel = new HashSet<>();

        // Process customers from Excel and match with existing customer_master records
        for (UploadedExcelSheet sheet : file.sheets()) {
            List<String> customerHeaders = sheet.headers().stream()
                    .filter(this::isCustomerHeader)
                    .toList();
            if (customerHeaders.isEmpty()) {
                continue;
            }
            List<String> amountHeaders = sheet.headers().stream()
                    .filter(this::isAmountHeader)
                    .toList();
            if (amountHeaders.isEmpty()) {
                continue;
            }

            List<String> totalHeaders = amountHeaders.stream()
                    .filter(header -> classifyAmountHeader(header) == AmountBucket.TOTAL)
                    .toList();

            for (Map<String, String> row : sheet.rows()) {
                Optional<String> customerValue = firstCustomerValue(row, customerHeaders);
                if (customerValue.isEmpty()) {
                    continue;
                }
                String displayName = customerValue.get();
                String key = normalizeCustomer(displayName);
                if (key.isBlank()) {
                    continue;
                }

                double rowTotal = sumAmounts(row, totalHeaders.isEmpty() ? amountHeaders : totalHeaders);
                if (rowTotal == 0) {
                    continue;
                }

                // Mark this customer as found in Excel
                customersInExcel.add(key);

                // Try to find existing customer_master record using fuzzy matching
                PaymentDateOverride matchedOverride = findFuzzyMatch(displayName, key, paymentDateOverrides, 0.7);
                
                // If fuzzy match found and key has changed, update customer_master record
                if (matchedOverride != null && !key.equals(matchedOverride.customerKey())) {
                    // Update the map with new key
                    paymentDateOverrides.remove(matchedOverride.customerKey());
                    PaymentDateOverride updated = new PaymentDateOverride(
                            matchedOverride.id(),
                            key,
                            displayName,
                            matchedOverride.nextPaymentDate(),
                            matchedOverride.phoneNumber(),
                            matchedOverride.whatsAppStatus(),
                            matchedOverride.customerCategory(),
                            true, // Mark as active since it's in the Excel file
                            matchedOverride.needsFollowUp() != null ? matchedOverride.needsFollowUp() : false,
                            matchedOverride.address(),
                            matchedOverride.place(),
                            matchedOverride.latitude(),
                            matchedOverride.longitude(),
                            matchedOverride.notes() != null ? matchedOverride.notes() : new ArrayList<>(),
                            Instant.now()
                    );
                    paymentDateOverrideRepository.save(updated);
                    paymentDateOverrides.put(key, updated);
                } else if (matchedOverride != null) {
                    // Exact match found - mark as active if needed
                    if (!matchedOverride.isActive()) {
                        PaymentDateOverride updated = new PaymentDateOverride(
                                matchedOverride.id(),
                                matchedOverride.customerKey(),
                                matchedOverride.customerName(),
                                matchedOverride.nextPaymentDate(),
                                matchedOverride.phoneNumber(),
                                matchedOverride.whatsAppStatus(),
                                matchedOverride.customerCategory(),
                                true, // Mark as active
                                matchedOverride.needsFollowUp() != null ? matchedOverride.needsFollowUp() : false,
                                matchedOverride.address(),
                                matchedOverride.place(),
                                matchedOverride.latitude(),
                                matchedOverride.longitude(),
                                matchedOverride.notes() != null ? matchedOverride.notes() : new ArrayList<>(),
                                Instant.now()
                        );
                        paymentDateOverrideRepository.save(updated);
                        paymentDateOverrides.put(key, updated);
                    } else {
                        paymentDateOverrides.put(key, matchedOverride);
                    }
                }

                aggregates.computeIfAbsent(key, ignored -> new PaymentDateAggregate(displayName))
                        .add(rowTotal);
            }
        }
        
        // Mark all customers not in Excel file as inactive
        for (PaymentDateOverride override : allOverridesList) {
            if (!customersInExcel.contains(override.customerKey()) && override.isActive()) {
                PaymentDateOverride updated = new PaymentDateOverride(
                        override.id(),
                        override.customerKey(),
                        override.customerName(),
                        override.nextPaymentDate(),
                        override.phoneNumber(),
                        override.whatsAppStatus(),
                        override.customerCategory(),
                        false, // Mark as inactive
                        override.needsFollowUp() != null ? override.needsFollowUp() : false,
                        override.address(),
                        override.place(),
                        override.latitude(),
                        override.longitude(),
                        override.notes() != null ? override.notes() : new ArrayList<>(),
                        Instant.now()
                );
                paymentDateOverrideRepository.save(updated);
                paymentDateOverrides.put(override.customerKey(), updated);
            }
        }

        // Build maps for payment dates, WhatsApp statuses, follow-up flags, and phones from updated customer_master
        Map<String, String> nextPaymentDates = paymentDateOverrides.values().stream()
                .filter(override -> override.nextPaymentDate() != null)
                .collect(Collectors.toMap(PaymentDateOverride::customerKey, PaymentDateOverride::nextPaymentDate, (a, b) -> a));
        Map<String, String> whatsAppStatuses = paymentDateOverrides.values().stream()
                .filter(override -> override.whatsAppStatus() != null)
                .collect(Collectors.toMap(PaymentDateOverride::customerKey, PaymentDateOverride::whatsAppStatus, (a, b) -> a));
        Map<String, String> customerCategories = paymentDateOverrides.values().stream()
                .filter(override -> override.customerCategory() != null)
                .collect(Collectors.toMap(PaymentDateOverride::customerKey, PaymentDateOverride::customerCategory, (a, b) -> a));
        Map<String, Boolean> needsFollowUpFlags = paymentDateOverrides.values().stream()
                .collect(Collectors.toMap(PaymentDateOverride::customerKey, 
                        override -> override.needsFollowUp() != null ? override.needsFollowUp() : false, 
                        (a, b) -> a));
        Map<String, String> customerPhones = paymentDateOverrides.values().stream()
                .filter(override -> override.phoneNumber() != null)
                .collect(Collectors.toMap(PaymentDateOverride::customerKey, PaymentDateOverride::phoneNumber, (a, b) -> a));
        Map<String, String> customerAddresses = paymentDateOverrides.values().stream()
                .filter(override -> override.address() != null && !override.address().isBlank())
                .collect(Collectors.toMap(PaymentDateOverride::customerKey, PaymentDateOverride::address, (a, b) -> a));
        Map<String, String> customerPlaces = paymentDateOverrides.values().stream()
                .filter(override -> override.place() != null && !override.place().isBlank())
                .collect(Collectors.toMap(PaymentDateOverride::customerKey, PaymentDateOverride::place, (a, b) -> a));
        Map<String, Double> customerLatitudes = paymentDateOverrides.values().stream()
                .filter(override -> override.latitude() != null)
                .collect(Collectors.toMap(PaymentDateOverride::customerKey, PaymentDateOverride::latitude, (a, b) -> a));
        Map<String, Double> customerLongitudes = paymentDateOverrides.values().stream()
                .filter(override -> override.longitude() != null)
                .collect(Collectors.toMap(PaymentDateOverride::customerKey, PaymentDateOverride::longitude, (a, b) -> a));

        List<PaymentDateCustomerCard> results = aggregates.values().stream()
                .sorted((a, b) -> Double.compare(b.totalAmount, a.totalAmount))
                .map(aggregate -> new PaymentDateCustomerCard(
                        aggregate.displayName,
                        aggregate.totalAmount,
                        nextPaymentDates.getOrDefault(aggregate.customerKey, null),
                        customerPhones.getOrDefault(aggregate.customerKey, null),
                        whatsAppStatuses.getOrDefault(aggregate.customerKey, "not sent"),
                        customerCategories.getOrDefault(aggregate.customerKey, null),
                        lastOrderDates.getOrDefault(aggregate.customerKey, null),
                        needsFollowUpFlags.getOrDefault(aggregate.customerKey, false),
                        customerAddresses.getOrDefault(aggregate.customerKey, null),
                        customerLatitudes.getOrDefault(aggregate.customerKey, null),
                        customerLongitudes.getOrDefault(aggregate.customerKey, null),
                        customerPlaces.getOrDefault(aggregate.customerKey, null)
                ))
                .toList();

        return ResponseEntity.ok(results);
    }

    @PostMapping("/payment-dates/next-date")
    public ResponseEntity<Map<String, Object>> updateNextPaymentDate(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody PaymentDateUpdateRequest request
    ) {
        try {
            SessionInfo session = authSessionService.validate(extractToken(authHeader));
            if (session == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", "Session expired or invalid"));
            }
            if (!SessionPermissions.canEditPaymentDate(session)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden", "message", "You do not have permission to edit payment dates."));
            }

            if (request == null || request.customer() == null || request.customer().isBlank()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid request", "message", "Customer name is required"));
            }

            String customerKey = normalizeCustomer(request.customer());
            if (customerKey.isBlank()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid customer", "message", "Customer name cannot be empty"));
            }

            String nextPaymentDate = request.nextPaymentDate() == null ? "" : request.nextPaymentDate().trim();

            // If date is blank, clear the date but preserve other fields
            if (nextPaymentDate.isBlank()) {
                PaymentDateOverride existing = paymentDateOverrideRepository.findFirstByCustomerKeyOrderByIdAsc(customerKey).orElse(null);
                if (existing == null) {
                    return ResponseEntity.ok(Map.of("success", true, "message", "Date cleared"));
                }
                paymentDateOverrideRepository.save(new PaymentDateOverride(
                        existing.id(),
                        customerKey,
                        existing.customerName(),
                        "",
                        existing.phoneNumber(),
                        existing.whatsAppStatus(),
                        existing.customerCategory(),
                        existing.isActive(),
                        existing.needsFollowUp() != null ? existing.needsFollowUp() : false,
                        existing.address(),
                        existing.place(),
                        existing.latitude(),
                        existing.longitude(),
                        existing.notes() != null ? existing.notes() : new ArrayList<>(),
                        Instant.now()
                ));
                return ResponseEntity.ok(Map.of("success", true, "message", "Date cleared successfully"));
            }
            
            // Validate date format
            if (!nextPaymentDate.matches("\\d{2}-\\d{2}")) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid date format", "message", "Date must be in DD-MM format"));
            }
            if (!isValidDayMonth(nextPaymentDate)) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid date", "message", "Date is not valid (e.g., day must be 1-31, month 1-12)"));
            }

            String phoneNumber = request.phoneNumber() == null ? null : request.phoneNumber().trim();
            if (phoneNumber != null && phoneNumber.isBlank()) {
                phoneNumber = null;
            }

            String whatsAppStatus = request.whatsAppStatus() == null ? null : request.whatsAppStatus().trim();
            if (whatsAppStatus != null && whatsAppStatus.isBlank()) {
                whatsAppStatus = null;
            }
            if (whatsAppStatus != null && !whatsAppStatus.equals("not sent") && !whatsAppStatus.equals("sent") && !whatsAppStatus.equals("delivered")) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid WhatsApp status", "message", "Status must be 'not sent', 'sent', or 'delivered'"));
            }

            PaymentDateOverride existing = paymentDateOverrideRepository.findFirstByCustomerKeyOrderByIdAsc(customerKey).orElse(null);
            String id = existing == null ? null : existing.id();
            String finalPhoneNumber = phoneNumber != null ? phoneNumber : (existing != null ? existing.phoneNumber() : null);
            String finalWhatsAppStatus = whatsAppStatus != null ? whatsAppStatus : (existing != null ? existing.whatsAppStatus() : null);
            String existingCustomerCategory = existing != null ? existing.customerCategory() : null;
            Boolean existingNeedsFollowUp = existing != null ? existing.needsFollowUp() : false;
            // Set active=true for manual updates (user is working with this customer)
            String existingAddress = existing != null ? existing.address() : null;
            String existingPlace = existing != null ? existing.place() : null;
            Double existingLatitude = existing != null ? existing.latitude() : null;
            Double existingLongitude = existing != null ? existing.longitude() : null;
            
            // Preserve existing notes when updating
            List<org.example.payment.CustomerNote> existingNotes = existing != null && existing.notes() != null 
                    ? existing.notes() 
                    : new ArrayList<>();
            
            PaymentDateOverride updated = new PaymentDateOverride(
                id, 
                customerKey, 
                request.customer().trim(), 
                nextPaymentDate, 
                finalPhoneNumber, 
                finalWhatsAppStatus, 
                existingCustomerCategory,
                true, 
                existingNeedsFollowUp != null ? existingNeedsFollowUp : false, 
                existingAddress, 
                existingPlace, 
                existingLatitude, 
                existingLongitude, 
                existingNotes,
                Instant.now()
            );
            
            paymentDateOverrideRepository.save(updated);
            return ResponseEntity.ok(Map.of("success", true, "message", "Payment date updated successfully"));
            
        } catch (Exception e) {
            logger.error("Error updating payment date", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error", "message", "Failed to update payment date. Please try again."));
        }
    }

    @PostMapping("/payment-dates/clear")
    public ResponseEntity<Void> clearAllNextPaymentDates(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!session.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        paymentDateOverrideRepository.deleteAll();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/payment-dates/whatsapp-status")
    public ResponseEntity<Map<String, Object>> updateWhatsAppStatus(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody WhatsAppStatusUpdateRequest request
    ) {
        try {
            SessionInfo session = authSessionService.validate(extractToken(authHeader));
            if (session == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", "Session expired or invalid"));
            }
            if (!SessionPermissions.canChangeWhatsappDate(session)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden", "message", "You do not have permission to change WhatsApp status."));
            }
            if (request == null || request.customer() == null || request.customer().isBlank()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid request", "message", "Customer name is required"));
            }
            String customerKey = normalizeCustomer(request.customer());
            if (customerKey.isBlank()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid customer", "message", "Customer name cannot be empty"));
            }

            String status = request.status() == null ? "not sent" : request.status().trim();
            if (!status.equals("not sent") && !status.equals("sent") && !status.equals("delivered")) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid status", "message", "Status must be 'not sent', 'sent', or 'delivered'"));
            }

            // Save to customer_master collection
            long rowsForKey = paymentDateOverrideRepository.countByCustomerKey(customerKey);
            if (rowsForKey > 1) {
                List<PaymentDateOverride> dupes = paymentDateOverrideRepository.findAllByCustomerKeyOrderByIdAsc(customerKey);
                List<String> dupeIds = dupes.stream().map(PaymentDateOverride::id).toList();
                logger.warn(
                        "updateWhatsAppStatus: {} customer_master rows share customerKey={}; only _id={} is updated (findFirstByOrderByIdAsc). All ids: {}",
                        rowsForKey,
                        customerKey,
                        dupes.isEmpty() ? "?" : dupes.getFirst().id(),
                        dupeIds
                );
            }
            PaymentDateOverride existingOverride = paymentDateOverrideRepository.findFirstByCustomerKeyOrderByIdAsc(customerKey).orElse(null);
            String overrideId = existingOverride == null ? null : existingOverride.id();
            String existingPhoneNumber = existingOverride != null ? existingOverride.phoneNumber() : null;
            String existingNextPaymentDate = existingOverride != null ? existingOverride.nextPaymentDate() : null;
            String existingCustomerCategory = existingOverride != null ? existingOverride.customerCategory() : null;
            Boolean existingNeedsFollowUp = existingOverride != null ? existingOverride.needsFollowUp() : null;
            // Set active=true for manual updates (user is working with this customer)
            // Always save to customer_master, even if no payment date exists
            String existingAddress = existingOverride != null ? existingOverride.address() : null;
            String existingPlace = existingOverride != null ? existingOverride.place() : null;
            Double existingLatitude = existingOverride != null ? existingOverride.latitude() : null;
            Double existingLongitude = existingOverride != null ? existingOverride.longitude() : null;
            
            List<org.example.payment.CustomerNote> existingNotes = existingOverride != null && existingOverride.notes() != null 
                    ? existingOverride.notes() 
                    : new ArrayList<>();
            
            logger.info(
                    "updateWhatsAppStatus: customerKey={}, displayName={}, mongoId={}, status={}",
                    customerKey,
                    request.customer().trim(),
                    overrideId != null ? overrideId : "(new)",
                    status
            );
            
            PaymentDateOverride updated = new PaymentDateOverride(
                    overrideId,
                    customerKey,
                    request.customer().trim(),
                    existingNextPaymentDate != null ? existingNextPaymentDate : "",
                    existingPhoneNumber,
                    status,
                    existingCustomerCategory,
                    true, // Mark as active since user is updating it
                    existingNeedsFollowUp != null ? existingNeedsFollowUp : false,
                    existingAddress,
                    existingPlace,
                    existingLatitude,
                    existingLongitude,
                    existingNotes,
                    Instant.now()
            );
            
            paymentDateOverrideRepository.save(updated);
            return ResponseEntity.ok(Map.of("success", true, "message", "WhatsApp status updated successfully"));
            
        } catch (Exception e) {
            logger.error("Error updating WhatsApp status", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error", "message", "Failed to update WhatsApp status. Please try again."));
        }
    }

    @GetMapping("/customer-ledger-debug")
    public ResponseEntity<CustomerLedgerDebugResponse> customerLedgerDebug(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam("customer") String customer
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        // SECURITY: Debug endpoint restricted to admin only - exposes internal data structure
        if (!session.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        
        // SECURITY: Validate and sanitize input
        if (customer == null || customer.isBlank() || customer.length() > 200) {
            return ResponseEntity.badRequest().build();
        }

        DetailedSalesInvoicesUpload latest = detailedSalesInvoicesUploadRepository.findTopByOrderByUploadedAtDesc();
        if (latest == null) {
            return ResponseEntity.ok(CustomerLedgerDebugResponse.empty());
        }

        String target = customer == null ? "" : normalizeCustomer(customer);
        UploadedExcelFile file = latest.file();

        for (UploadedExcelSheet sheet : file.sheets()) {
            List<String> customerHeaders = sheet.headers().stream()
                    .filter(this::isCustomerHeader)
                    .toList();
            if (customerHeaders.isEmpty()) {
                continue;
            }

            List<String> invoiceHeaders = sheet.headers().stream()
                    .filter(this::isInvoiceDateHeader)
                    .toList();
            List<String> voucherHeaders = sheet.headers().stream()
                    .filter(this::isVoucherHeader)
                    .toList();
            List<String> receivedHeaders = sheet.headers().stream()
                    .filter(this::isReceivedHeader)
                    .toList();
            List<String> dueHeaders = sheet.headers().stream()
                    .filter(this::isCurrentDueHeader)
                    .toList();

            for (Map<String, String> row : sheet.rows()) {
                Optional<String> matchedCustomer = target.isBlank()
                        ? Optional.of("any")
                        : findCustomerValue(row, customerHeaders, target);
                if (matchedCustomer.isEmpty()) {
                    continue;
                }
                return ResponseEntity.ok(new CustomerLedgerDebugResponse(
                        sheet.name(),
                        customerHeaders,
                        invoiceHeaders,
                        voucherHeaders,
                        receivedHeaders,
                        dueHeaders,
                        row
                ));
            }
        }

        return ResponseEntity.ok(CustomerLedgerDebugResponse.empty());
    }

    @GetMapping("/sales-invoice-headers")
    public ResponseEntity<List<SalesInvoiceHeadersResponse>> salesInvoiceHeaders(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessInvoicePage(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        DetailedSalesInvoicesUpload latest = detailedSalesInvoicesUploadRepository.findTopByOrderByUploadedAtDesc();
        if (latest == null) {
            return ResponseEntity.ok(List.of());
        }

        List<SalesInvoiceHeadersResponse> responses = new ArrayList<>();
        for (UploadedExcelSheet sheet : latest.file().sheets()) {
            responses.add(new SalesInvoiceHeadersResponse(sheet.name(), sheet.headers()));
        }
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/sales-invoices")
    public ResponseEntity<SalesInvoicePageResponse> salesInvoices(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody org.example.api.request.SalesInvoiceSearchRequest request
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessInvoicePage(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        // SECURITY: Extract from POST body instead of URL query parameters
        String customerFilter = request != null ? request.customer() : null;
        String phoneFilter = request != null ? request.phone() : null;
        String voucherNoFilter = request != null ? request.voucherNo() : null;
        String dateFrom = request != null ? request.dateFrom() : null;
        String dateTo = request != null ? request.dateTo() : null;
        Double receivedAmountMin = request != null ? request.receivedAmountMin() : null;
        Double receivedAmountMax = request != null ? request.receivedAmountMax() : null;
        Double currentDueMin = request != null ? request.currentDueMin() : null;
        Double currentDueMax = request != null ? request.currentDueMax() : null;
        Integer ageingDaysMin = request != null ? request.ageingDaysMin() : null;
        Integer ageingDaysMax = request != null ? request.ageingDaysMax() : null;
        String ageingBucket = request != null ? request.ageingBucket() : null;
        Double totalAmountMin = request != null ? request.totalAmountMin() : null;
        Double totalAmountMax = request != null ? request.totalAmountMax() : null;
        Integer year = request != null ? request.year() : null;
        Integer month = request != null ? request.month() : null;
        Integer quarter = request != null ? request.quarter() : null;
        String status = request != null ? request.status() : null;
        String sortBy = request != null ? request.sortBy() : null;
        String sortOrder = request != null ? request.sortOrder() : "asc";
        int page = request != null && request.page() != null ? request.page() : 0;
        int size = request != null && request.size() != null ? request.size() : 15;

        DetailedSalesInvoicesUpload latest = detailedSalesInvoicesUploadRepository.findTopByOrderByUploadedAtDesc();
        if (latest == null) {
            return ResponseEntity.ok(new SalesInvoicePageResponse(
                    List.of(),
                    0,
                    1,
                    page,
                    size
            ));
        }

        UploadedExcelFile file = latest.file();
        List<SalesInvoiceEntry> entries = new ArrayList<>();

        // Build customer phone map from customer_master for quick lookup
        Map<String, String> customerPhoneMap = buildCustomerPhoneMap();

        for (UploadedExcelSheet sheet : file.sheets()) {
            List<String> customerHeaders = sheet.headers().stream()
                    .filter(this::isCustomerHeader)
                    .toList();
            List<String> invoiceHeaders = sheet.headers().stream()
                    .filter(this::isInvoiceDateHeader)
                    .toList();
            List<String> voucherHeaders = sheet.headers().stream()
                    .filter(this::isVoucherHeader)
                    .toList();
            List<String> receivedHeaders = sheet.headers().stream()
                    .filter(this::isReceivedHeader)
                    .toList();
            List<String> dueHeaders = sheet.headers().stream()
                    .filter(this::isCurrentDueHeader)
                    .toList();

            for (Map<String, String> row : sheet.rows()) {
                String customer = firstNonBlank(row, customerHeaders);
                if (customer == null || customer.isBlank()) {
                    continue;
                }

                // Apply customer filter
                if (customerFilter != null && !customerFilter.isBlank()) {
                    String normalizedFilter = normalizeCustomer(customerFilter);
                    String normalizedCustomer = normalizeCustomer(customer);
                    if (!normalizedCustomer.contains(normalizedFilter) && !normalizedFilter.contains(normalizedCustomer)) {
                        continue;
                    }
                }

                // Get phone number from customer_master only
                String key = normalizeCustomer(customer);
                String customerPhone = customerPhoneMap.get(key);

                // Apply phone filter
                if (phoneFilter != null && !phoneFilter.isBlank()) {
                    if (customerPhone == null || !customerPhone.contains(phoneFilter)) {
                        continue;
                    }
                }

                String invoiceDate = firstNonBlank(row, invoiceHeaders);
                String voucherNo = firstNonBlank(row, voucherHeaders);

                // Apply voucher filter
                if (voucherNoFilter != null && !voucherNoFilter.isBlank()) {
                    if (voucherNo == null || !voucherNo.toLowerCase(Locale.ROOT).contains(voucherNoFilter.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                }

                // Apply date filters
                if (dateFrom != null && !dateFrom.isBlank() && invoiceDate != null) {
                    LocalDate invoiceLocalDate = parseInvoiceDate(invoiceDate);
                    LocalDate fromDate = parseInvoiceDate(dateFrom);
                    if (invoiceLocalDate != null && fromDate != null && invoiceLocalDate.isBefore(fromDate)) {
                        continue;
                    }
                }
                if (dateTo != null && !dateTo.isBlank() && invoiceDate != null) {
                    LocalDate invoiceLocalDate = parseInvoiceDate(invoiceDate);
                    LocalDate toDate = parseInvoiceDate(dateTo);
                    if (invoiceLocalDate != null && toDate != null && invoiceLocalDate.isAfter(toDate)) {
                        continue;
                    }
                }

                double receivedAmount = sumAmounts(row, receivedHeaders);
                double currentDue = sumAmounts(row, dueHeaders);
                double totalAmount = receivedAmount + currentDue;
                // Only compute ageing days when there's an amount due
                Integer ageingDays = (currentDue > 0.01) ? computeAgeingDays(invoiceDate) : null;
                LocalDate invoiceLocalDate = parseInvoiceDate(invoiceDate);

                // Apply received amount filters
                if (receivedAmountMin != null && receivedAmount < receivedAmountMin) {
                    continue;
                }
                if (receivedAmountMax != null && receivedAmount > receivedAmountMax) {
                    continue;
                }

                // Apply current due filters
                if (currentDueMin != null && currentDue < currentDueMin) {
                    continue;
                }
                if (currentDueMax != null && currentDue > currentDueMax) {
                    continue;
                }

                // Apply total amount filters
                if (totalAmountMin != null && totalAmount < totalAmountMin) {
                    continue;
                }
                if (totalAmountMax != null && totalAmount > totalAmountMax) {
                    continue;
                }

                // Apply ageing days filters
                if (ageingDaysMin != null && (ageingDays == null || ageingDays < ageingDaysMin)) {
                    continue;
                }
                if (ageingDaysMax != null && (ageingDays == null || ageingDays > ageingDaysMax)) {
                    continue;
                }

                // Apply ageing bucket filter
                if (ageingBucket != null && !ageingBucket.isBlank() && ageingDays != null) {
                    String bucket = ageingBucket.toLowerCase(Locale.ROOT);
                    if (bucket.equals("1-45") && (ageingDays < 1 || ageingDays > 45)) {
                        continue;
                    } else if (bucket.equals("46-85") && (ageingDays < 46 || ageingDays > 85)) {
                        continue;
                    } else if (bucket.equals("90+") && ageingDays <= 85) {
                        continue;
                    }
                }

                // Apply year filter
                if (year != null && invoiceLocalDate != null && invoiceLocalDate.getYear() != year) {
                    continue;
                }

                // Apply month filter (1-12)
                if (month != null && invoiceLocalDate != null && invoiceLocalDate.getMonthValue() != month) {
                    continue;
                }

                // Apply quarter filter (1-4)
                if (quarter != null && invoiceLocalDate != null) {
                    int invoiceQuarter = (invoiceLocalDate.getMonthValue() - 1) / 3 + 1;
                    if (invoiceQuarter != quarter) {
                        continue;
                    }
                }

                // Apply status filter
                if (status != null && !status.isBlank()) {
                    String statusLower = status.toLowerCase(Locale.ROOT);
                    if (statusLower.equals("paid")) {
                        // Paid: currentDue must be 0 (fully paid)
                        if (currentDue > 0) {
                            continue;
                        }
                    } else if (statusLower.equals("unpaid")) {
                        // Unpaid: receivedAmount must be 0 (nothing received)
                        if (receivedAmount > 0) {
                            continue;
                        }
                    } else if (statusLower.equals("partial")) {
                        // Partial: both receivedAmount > 0 and currentDue > 0 (partially paid)
                        if (receivedAmount == 0 || currentDue == 0) {
                            continue;
                        }
                    }
                }

                entries.add(new SalesInvoiceEntry(
                        invoiceDate,
                        voucherNo,
                        customer,
                        customerPhone != null ? customerPhone : "",
                        receivedAmount,
                        currentDue,
                        ageingDays
                ));
            }
        }

        // Apply sorting
        if (sortBy != null && !sortBy.isBlank()) {
            String sortByLower = sortBy.toLowerCase(Locale.ROOT);
            boolean ascending = !"desc".equalsIgnoreCase(sortOrder);
            
            entries.sort((a, b) -> {
                int result = 0;
                switch (sortByLower) {
                    case "invoicedate":
                    case "date":
                        result = compareDates(a.invoiceDate(), b.invoiceDate());
                        break;
                    case "voucherno":
                    case "voucher":
                        result = compareStrings(a.voucherNo(), b.voucherNo());
                        break;
                    case "customer":
                        result = compareStrings(a.customer(), b.customer());
                        break;
                    case "phone":
                    case "customerphone":
                        result = compareStrings(a.customerPhone(), b.customerPhone());
                        break;
                    case "receivedamount":
                    case "received":
                        result = Double.compare(a.receivedAmount(), b.receivedAmount());
                        break;
                    case "currentdue":
                    case "due":
                        result = Double.compare(a.currentDue(), b.currentDue());
                        break;
                    case "ageingdays":
                    case "ageing":
                        result = compareIntegers(a.ageingDays(), b.ageingDays());
                        break;
                    default:
                        return 0;
                }
                return ascending ? result : -result;
            });
        }

        // Calculate pagination
        int totalElements = entries.size();
        int totalPages = totalElements == 0 ? 1 : (int) Math.ceil((double) totalElements / size);
        
        // Apply pagination
        int start = page * size;
        int end = Math.min(start + size, entries.size());
        if (start >= entries.size()) {
            return ResponseEntity.ok(new SalesInvoicePageResponse(
                    List.of(),
                    totalElements,
                    totalPages,
                    page,
                    size
            ));
        }
        
        List<SalesInvoiceEntry> pageContent = entries.subList(start, end);
        return ResponseEntity.ok(new SalesInvoicePageResponse(
                pageContent,
                totalElements,
                totalPages,
                page,
                size
        ));
    }

    private boolean isCustomerHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("customer");
    }

    private boolean isPhoneHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("mobile")
                || normalized.contains("phone")
                || normalized.contains("contact");
    }

    private boolean isCustomerPhoneHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        // Specifically match "Customer Phone" column name
        if (normalized.equals("customer phone")) {
            return true;
        }
        return normalized.contains("customer") && isPhoneHeader(header);
    }

    private boolean isAmountHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        boolean hasDigits = normalized.matches(".*\\d+.*");
        return normalized.contains("amount")
                || normalized.contains("outstanding")
                || normalized.contains("balance")
                || normalized.contains("total")
                || (hasDigits && (normalized.contains("days") || normalized.contains("ageing") || normalized.contains("aging")));
    }

    private boolean isInvoiceDateHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("invoice date")
                || (normalized.contains("invoice") && normalized.contains("date"));
    }

    private boolean isVoucherHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("voucher no.")
                || normalized.equals("voucher no")
                || normalized.contains("voucher")
                || normalized.contains("invoice no")
                || normalized.contains("inv no")
                || normalized.contains("bill no");
    }

    private boolean isReceivedHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("date")) {
            return false;
        }
        return normalized.equals("received amount")
                || normalized.contains("received amount")
                || normalized.contains("received")
                || normalized.contains("receipt")
                || normalized.contains("rcvd")
                || normalized.contains("rcv")
                || normalized.contains("paid")
                || normalized.contains("payment")
                || normalized.contains("collection");
    }

    private boolean isCurrentDueHeader(String header) {
        if (header == null) {
            return false;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("date") || normalized.contains("days") || normalized.contains("ageing") || normalized.contains("aging")) {
            return false;
        }
        return normalized.equals("current due")
                || normalized.contains("current due")
                || normalized.contains("current outstanding")
                || normalized.contains("current")
                || normalized.contains("due amount")
                || normalized.contains("outstanding")
                || normalized.contains("balance")
                || normalized.contains("balance amt")
                || normalized.contains("pending")
                || normalized.equals("due");
    }

    private Optional<String> findCustomerValue(Map<String, String> row,
                                               List<String> headers,
                                               String target) {
        for (String header : headers) {
            String value = row.get(header);
            if (value == null || value.isBlank()) {
                continue;
            }
            if (matchesCustomer(value, target)) {
                return Optional.of(value.trim());
            }
        }
        return Optional.empty();
    }

    private String normalizeCustomer(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
        return normalized;
    }

    private boolean matchesCustomer(String value, String target) {
        if (target == null || target.isBlank()) {
            return true;
        }
        String normalizedValue = normalizeCustomer(value);
        String normalizedTarget = normalizeCustomer(target);
        if (normalizedValue.isBlank() || normalizedTarget.isBlank()) {
            return false;
        }
        if (normalizedValue.equals(normalizedTarget)
                || normalizedValue.contains(normalizedTarget)
                || normalizedTarget.contains(normalizedValue)) {
            return true;
        }
        List<String> valueTokens = tokenize(normalizedValue);
        List<String> targetTokens = tokenize(normalizedTarget);
        return valueTokens.containsAll(targetTokens);
    }

    private List<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split("\\s+"));
    }

    private String firstNonBlank(Map<String, String> row, List<String> headers) {
        for (String header : headers) {
            String value = row.get(header);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private Optional<String> firstCustomerValue(Map<String, String> row, List<String> headers) {
        for (String header : headers) {
            String value = row.get(header);
            if (value != null && !value.isBlank()) {
                return Optional.of(value.trim());
            }
        }
        return Optional.empty();
    }

    private double sumAmounts(Map<String, String> row, List<String> headers) {
        double total = 0.0;
        for (String header : headers) {
            total += parseAmount(row.get(header));
        }
        return total;
    }

    private String findCustomerPhone(String customer) {
        String customerKey = normalizeCustomer(customer);
        PaymentDateOverride override = paymentDateOverrideRepository.findFirstByCustomerKeyOrderByIdAsc(customerKey).orElse(null);
        if (override != null && override.phoneNumber() != null && !override.phoneNumber().isBlank()) {
            return override.phoneNumber().trim();
        }
        return null;
    }

    private String findCustomerByPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String phoneTrimmed = phone.trim();
        List<PaymentDateOverride> allOverrides = paymentDateOverrideRepository.findAll();
        for (PaymentDateOverride override : allOverrides) {
            if (override.phoneNumber() != null && !override.phoneNumber().isBlank()) {
                String storedPhone = override.phoneNumber().trim();
                if (storedPhone.equals(phoneTrimmed) || storedPhone.contains(phoneTrimmed) || phoneTrimmed.contains(storedPhone)) {
                    return override.customerName();
                }
            }
        }
        return null;
    }

    private double parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return 0.0;
        }
        String cleaned = value.replace(",", "")
                .replaceAll("[^0-9.\\-]", "");
        if (cleaned.isBlank() || "-".equals(cleaned) || ".".equals(cleaned)) {
            return 0.0;
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private AmountBucket classifyAmountHeader(String header) {
        if (header == null) {
            return AmountBucket.UNKNOWN;
        }
        String normalized = header.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("total")) {
            return AmountBucket.TOTAL;
        }
        List<Integer> numbers = extractNumbers(normalized);
        if (numbers.isEmpty()) {
            if (normalized.contains("current")) {
                return AmountBucket.WITHIN;
            }
            return AmountBucket.UNKNOWN;
        }
        boolean hasPlus = normalized.contains("+") || normalized.contains("above") || normalized.contains("more");
        if (hasPlus) {
            return numbers.get(0) > 45 ? AmountBucket.BEYOND : AmountBucket.WITHIN;
        }
        if (numbers.size() >= 2) {
            int min = numbers.get(0);
            int max = numbers.get(1);
            if (max <= 45) {
                return AmountBucket.WITHIN;
            }
            if (min >= 86) {
                return AmountBucket.BEYOND;
            }
            return AmountBucket.MID;
        }
        int value = numbers.get(0);
        if (value <= 45) {
            return AmountBucket.WITHIN;
        }
        if (value >= 86) {
            return AmountBucket.BEYOND;
        }
        return AmountBucket.MID;
    }

    private List<Integer> extractNumbers(String text) {
        List<Integer> numbers = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\d+").matcher(text);
        while (matcher.find()) {
            try {
                numbers.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException ignored) {
                // ignore parse errors
            }
        }
        return numbers;
    }

    private Integer computeAgeingDays(String invoiceDate) {
        if (invoiceDate == null || invoiceDate.isBlank()) {
            return null;
        }
        LocalDate date = parseInvoiceDate(invoiceDate.trim());
        if (date == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(date, LocalDate.now(ZoneId.systemDefault()));
        return (int) Math.max(0, days);
    }

    private LocalDate parseInvoiceDate(String invoiceDate) {
        // Normalize "Sept" to "Sep" to handle non-standard month abbreviation
        String normalizedDate = invoiceDate.replaceAll("(?i)-Sept-", "-Sep-");
        
        for (DateTimeFormatter formatter : INVOICE_DATE_TIME_FORMATTERS) {
            try {
                LocalDateTime dateTime = LocalDateTime.parse(normalizedDate, formatter);
                return dateTime.toLocalDate();
            } catch (DateTimeParseException ignored) {
                // try next formatter
            }
        }
        for (DateTimeFormatter formatter : INVOICE_DATE_FORMATTERS) {
            try {
                return LocalDate.parse(normalizedDate, formatter);
            } catch (DateTimeParseException ignored) {
                // try next formatter
            }
        }
        return null;
    }

    private int compareDates(String date1, String date2) {
        LocalDate d1 = date1 != null ? parseInvoiceDate(date1) : null;
        LocalDate d2 = date2 != null ? parseInvoiceDate(date2) : null;
        if (d1 == null && d2 == null) return 0;
        if (d1 == null) return -1;
        if (d2 == null) return 1;
        return d1.compareTo(d2);
    }

    private int compareStrings(String s1, String s2) {
        if (s1 == null && s2 == null) return 0;
        if (s1 == null) return -1;
        if (s2 == null) return 1;
        return s1.compareToIgnoreCase(s2);
    }

    private int compareIntegers(Integer i1, Integer i2) {
        if (i1 == null && i2 == null) return 0;
        if (i1 == null) return -1;
        if (i2 == null) return 1;
        return i1.compareTo(i2);
    }

    private enum AmountBucket {
        WITHIN,
        MID,
        BEYOND,
        TOTAL,
        UNKNOWN
    }

    private final class PaymentDateAggregate {
        private final String displayName;
        private final String customerKey;
        private double totalAmount;

        private PaymentDateAggregate(String displayName) {
            this.displayName = displayName;
            this.customerKey = normalizeCustomer(displayName);
        }

        private void add(double amount) {
            this.totalAmount += amount;
        }
    }

    private record PaymentDateUpdateRequest(String customer, String nextPaymentDate, String phoneNumber, String whatsAppStatus) {
    }

    private record WhatsAppStatusUpdateRequest(String customer, String status) {
    }

    private record CustomerCategoryUpdateRequest(String customer, String category) {
    }

    @PostMapping("/payment-dates/customer-category")
    public ResponseEntity<Map<String, Object>> updateCustomerCategory(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody CustomerCategoryUpdateRequest request
    ) {
        try {
            SessionInfo session = authSessionService.validate(extractToken(authHeader));
            if (session == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", "Session expired or invalid"));
            }
            if (!SessionPermissions.canEditCustomerCategory(session)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden", "message", "You do not have permission to edit customer category."));
            }
            if (request == null || request.customer() == null || request.customer().isBlank()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid request", "message", "Customer name is required"));
            }
            String customerKey = normalizeCustomer(request.customer());
            if (customerKey.isBlank()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid customer", "message", "Customer name cannot be empty"));
            }

            String category = request.category() == null ? null : request.category().trim();
            if (category != null && !category.equals("semi-wholesale") && !category.equals("A") && !category.equals("B") && !category.equals("C")) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid category", "message", "Category must be 'semi-wholesale', 'A', 'B', or 'C'"));
            }

            // Save to customer_master collection
            PaymentDateOverride existingOverride = paymentDateOverrideRepository.findFirstByCustomerKeyOrderByIdAsc(customerKey).orElse(null);
            String overrideId = existingOverride == null ? null : existingOverride.id();
            String existingPhoneNumber = existingOverride != null ? existingOverride.phoneNumber() : null;
            String existingNextPaymentDate = existingOverride != null ? existingOverride.nextPaymentDate() : null;
            String existingWhatsAppStatus = existingOverride != null ? existingOverride.whatsAppStatus() : null;
            Boolean existingNeedsFollowUp = existingOverride != null ? existingOverride.needsFollowUp() : null;
            String existingAddress = existingOverride != null ? existingOverride.address() : null;
            String existingPlace = existingOverride != null ? existingOverride.place() : null;
            Double existingLatitude = existingOverride != null ? existingOverride.latitude() : null;
            Double existingLongitude = existingOverride != null ? existingOverride.longitude() : null;
            
            List<org.example.payment.CustomerNote> existingNotes = existingOverride != null && existingOverride.notes() != null 
                    ? existingOverride.notes() 
                    : new ArrayList<>();
            
            PaymentDateOverride updated = new PaymentDateOverride(
                    overrideId,
                    customerKey,
                    request.customer().trim(),
                    existingNextPaymentDate != null ? existingNextPaymentDate : "",
                    existingPhoneNumber,
                    existingWhatsAppStatus,
                    category,
                    true, // Mark as active since user is updating it
                    existingNeedsFollowUp != null ? existingNeedsFollowUp : false,
                    existingAddress,
                    existingPlace,
                    existingLatitude,
                    existingLongitude,
                    existingNotes,
                    Instant.now()
            );
            
            paymentDateOverrideRepository.save(updated);
            return ResponseEntity.ok(Map.of("success", true, "message", "Customer category updated successfully"));
            
        } catch (Exception e) {
            logger.error("Error updating customer category", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error", "message", "Failed to update customer category. Please try again."));
        }
    }

    private record PlaceUpdateRequest(String customer, String place) {
    }

    @PostMapping("/payment-dates/place")
    public ResponseEntity<Map<String, Object>> updatePlace(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody PlaceUpdateRequest request
    ) {
        try {
            SessionInfo session = authSessionService.validate(extractToken(authHeader));
            if (session == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", "Session expired or invalid"));
            }
            if (!SessionPermissions.canEditCustomerLocation(session)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden", "message", "You do not have permission to edit place."));
            }
            if (request == null || request.customer() == null || request.customer().isBlank()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid request", "message", "Customer name is required"));
            }
            String customerKey = normalizeCustomer(request.customer());
            if (customerKey.isBlank()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid customer", "message", "Customer name cannot be empty"));
            }

            String place = request.place() == null ? null : request.place().trim();
            if (place != null && place.isBlank()) {
                place = null;
            }

            PaymentDateOverride existingOverride = paymentDateOverrideRepository.findFirstByCustomerKeyOrderByIdAsc(customerKey).orElse(null);
            String overrideId = existingOverride == null ? null : existingOverride.id();
            String existingPhoneNumber = existingOverride != null ? existingOverride.phoneNumber() : null;
            String existingNextPaymentDate = existingOverride != null ? existingOverride.nextPaymentDate() : null;
            String existingWhatsAppStatus = existingOverride != null ? existingOverride.whatsAppStatus() : null;
            String existingCustomerCategory = existingOverride != null ? existingOverride.customerCategory() : null;
            Boolean existingNeedsFollowUp = existingOverride != null ? existingOverride.needsFollowUp() : null;
            String existingAddress = existingOverride != null ? existingOverride.address() : null;
            Double existingLatitude = existingOverride != null ? existingOverride.latitude() : null;
            Double existingLongitude = existingOverride != null ? existingOverride.longitude() : null;

            List<org.example.payment.CustomerNote> existingNotes = existingOverride != null && existingOverride.notes() != null
                    ? existingOverride.notes()
                    : new ArrayList<>();

            PaymentDateOverride updated = new PaymentDateOverride(
                    overrideId,
                    customerKey,
                    request.customer().trim(),
                    existingNextPaymentDate != null ? existingNextPaymentDate : "",
                    existingPhoneNumber,
                    existingWhatsAppStatus,
                    existingCustomerCategory,
                    true,
                    existingNeedsFollowUp != null ? existingNeedsFollowUp : false,
                    existingAddress,
                    place,
                    existingLatitude,
                    existingLongitude,
                    existingNotes,
                    Instant.now()
            );

            paymentDateOverrideRepository.save(updated);
            return ResponseEntity.ok(Map.of("success", true, "message", "Place updated successfully"));

        } catch (Exception e) {
            logger.error("Error updating place", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error", "message", "Failed to update place. Please try again."));
        }
    }

    @PostMapping("/payment-dates/follow-up")
    public ResponseEntity<Map<String, Object>> updateFollowUpFlag(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody FollowUpUpdateRequest request
    ) {
        try {
            SessionInfo session = authSessionService.validate(extractToken(authHeader));
            if (session == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", "Session expired or invalid"));
            }
            if (!SessionPermissions.canChangeFollowUp(session)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden", "message", "You do not have permission to change follow-up flags."));
            }
            if (request == null || request.customer() == null || request.customer().isBlank()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid request", "message", "Customer name is required"));
            }
            String customerKey = normalizeCustomer(request.customer());
            if (customerKey.isBlank()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid customer", "message", "Customer name cannot be empty"));
            }

            Boolean needsFollowUp = request.needsFollowUp() != null ? request.needsFollowUp() : false;

            // Save to customer_master collection
            PaymentDateOverride existingOverride = paymentDateOverrideRepository.findFirstByCustomerKeyOrderByIdAsc(customerKey).orElse(null);
            String overrideId = existingOverride == null ? null : existingOverride.id();
            String existingPhoneNumber = existingOverride != null ? existingOverride.phoneNumber() : null;
            String existingNextPaymentDate = existingOverride != null ? existingOverride.nextPaymentDate() : null;
            String existingWhatsAppStatus = existingOverride != null ? existingOverride.whatsAppStatus() : null;
            String existingCustomerCategory = existingOverride != null ? existingOverride.customerCategory() : null;
            // Set active=true for manual updates (user is working with this customer)
            // Always save to customer_master, even if no payment date exists
            String existingAddress = existingOverride != null ? existingOverride.address() : null;
            String existingPlace = existingOverride != null ? existingOverride.place() : null;
            Double existingLatitude = existingOverride != null ? existingOverride.latitude() : null;
            Double existingLongitude = existingOverride != null ? existingOverride.longitude() : null;
            
            List<org.example.payment.CustomerNote> existingNotes = existingOverride != null && existingOverride.notes() != null 
                    ? existingOverride.notes() 
                    : new ArrayList<>();
            
            PaymentDateOverride updated = new PaymentDateOverride(
                    overrideId,
                    customerKey,
                    request.customer().trim(),
                    existingNextPaymentDate != null ? existingNextPaymentDate : "",
                    existingPhoneNumber,
                    existingWhatsAppStatus != null ? existingWhatsAppStatus : "not sent",
                    existingCustomerCategory,
                    true, // Mark as active since user is updating it
                    needsFollowUp,
                    existingAddress,
                    existingPlace,
                    existingLatitude,
                    existingLongitude,
                    existingNotes,
                    Instant.now()
            );
            
            paymentDateOverrideRepository.save(updated);
            return ResponseEntity.ok(Map.of("success", true, "message", "Follow-up flag updated successfully"));
            
        } catch (Exception e) {
            logger.error("Error updating follow-up flag", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error", "message", "Failed to update follow-up flag. Please try again."));
        }
    }

    private record FollowUpUpdateRequest(String customer, Boolean needsFollowUp) {
    }

    @PostMapping("/payment-dates/location")
    public ResponseEntity<Void> updateCustomerLocation(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody LocationUpdateRequest request
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canEditCustomerLocation(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (request == null || request.customer() == null || request.customer().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String customerKey = normalizeCustomer(request.customer());
        if (customerKey.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        PaymentDateOverride existing = paymentDateOverrideRepository.findFirstByCustomerKeyOrderByIdAsc(customerKey).orElse(null);
        String id = existing == null ? null : existing.id();
        
        // Handle location values: 
        // - Empty string for address means delete (set to null)
        // - If address is empty string AND coordinates are null, delete all location data
        // - Otherwise, preserve existing values if new values are null/not provided
        String address;
        if (request.address() != null) {
            // Address is provided in request - empty string means delete
            address = request.address().trim().isBlank() ? null : request.address().trim();
        } else {
            // Address not provided - preserve existing
            address = existing != null ? existing.address() : null;
        }
        
        Double latitude;
        Double longitude;
        
        // If address is being deleted (empty string) and coordinates are explicitly null, delete all location
        boolean isDeletingAll = request.address() != null && request.address().trim().isBlank() 
            && request.latitude() == null && request.longitude() == null;
        
        if (isDeletingAll) {
            // Full deletion - set all to null
            latitude = null;
            longitude = null;
        } else {
            // Normal update - preserve existing if not provided
            latitude = request.latitude() != null ? request.latitude() : (existing != null ? existing.latitude() : null);
            longitude = request.longitude() != null ? request.longitude() : (existing != null ? existing.longitude() : null);
        }
        
        // Preserve other existing fields
        String existingCustomerName = existing != null ? existing.customerName() : request.customer().trim();
        String existingNextPaymentDate = existing != null ? existing.nextPaymentDate() : null;
        String existingPhoneNumber = existing != null ? existing.phoneNumber() : null;
        String existingWhatsAppStatus = existing != null ? existing.whatsAppStatus() : null;
        String existingCustomerCategory = existing != null ? existing.customerCategory() : null;
        Boolean existingNeedsFollowUp = existing != null ? existing.needsFollowUp() : false;
        
        // Set active=true for manual updates (user is working with this customer)
        List<org.example.payment.CustomerNote> existingNotes = existing != null && existing.notes() != null 
                ? existing.notes() 
                : new ArrayList<>();
        
        String existingPlace = existing != null ? existing.place() : null;
        paymentDateOverrideRepository.save(new PaymentDateOverride(
                id,
                customerKey,
                existingCustomerName,
                existingNextPaymentDate != null ? existingNextPaymentDate : "",
                existingPhoneNumber,
                existingWhatsAppStatus != null ? existingWhatsAppStatus : "not sent",
                existingCustomerCategory,
                true,
                existingNeedsFollowUp != null ? existingNeedsFollowUp : false,
                address,
                existingPlace,
                latitude,
                longitude,
                existingNotes,
                Instant.now()
        ));
        
        return ResponseEntity.ok().build();
    }

    private record LocationUpdateRequest(String customer, String address, Double latitude, Double longitude) {
    }

    @GetMapping("/customers/locations")
    public ResponseEntity<List<CustomerLocationResponse>> getCustomerLocations(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessCustomerLocations(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<PaymentDateOverride> allOverrides = paymentDateOverrideRepository.findAll();
        List<CustomerLocationResponse> locations = allOverrides.stream()
                .filter(override -> {
                    // Filter to customers with location data
                    return (override.address() != null && !override.address().isBlank()) 
                            || override.latitude() != null 
                            || override.longitude() != null;
                })
                .map(override -> new CustomerLocationResponse(
                        override.customerName(),
                        override.phoneNumber(),
                        override.address(),
                        override.latitude(),
                        override.longitude()
                ))
                .toList();

        return ResponseEntity.ok(locations);
    }

    private record CustomerLocationResponse(
            String customerName,
            String phoneNumber,
            String address,
            Double latitude,
            Double longitude
    ) {
    }

    private boolean isValidDayMonth(String value) {
        try {
            String[] parts = value.split("-");
            int day = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            int year = Year.now().getValue();
            LocalDate.of(year, month, day);
            return true;
        } catch (DateTimeException | NumberFormatException ex) {
            return false;
        }
    }

    private Map<String, String> buildCustomerPhoneMap() {
        List<PaymentDateOverride> allOverrides = paymentDateOverrideRepository.findAll();
        Map<String, String> phoneMap = new java.util.HashMap<>();
        for (PaymentDateOverride override : allOverrides) {
            if (override.phoneNumber() != null && !override.phoneNumber().isBlank()) {
                phoneMap.put(override.customerKey(), override.phoneNumber().trim());
            }
        }
        return phoneMap;
    }

    private Map<String, String> buildLastOrderDateMap() {
        DetailedSalesInvoicesUpload latest = detailedSalesInvoicesUploadRepository.findTopByOrderByUploadedAtDesc();
        if (latest == null) {
            return Map.of();
        }
        Map<String, LocalDate> lastOrderDateMap = new java.util.HashMap<>();
        UploadedExcelFile file = latest.file();

        for (UploadedExcelSheet sheet : file.sheets()) {
            List<String> customerHeaders = sheet.headers().stream()
                    .filter(this::isCustomerHeader)
                    .toList();
            List<String> invoiceHeaders = sheet.headers().stream()
                    .filter(this::isInvoiceDateHeader)
                    .toList();
            if (customerHeaders.isEmpty() || invoiceHeaders.isEmpty()) {
                continue;
            }
            for (Map<String, String> row : sheet.rows()) {
                Optional<String> customerValue = firstCustomerValue(row, customerHeaders);
                if (customerValue.isEmpty()) {
                    continue;
                }
                String key = normalizeCustomer(customerValue.get());
                if (key.isBlank()) {
                    continue;
                }
                String invoiceDate = firstNonBlank(row, invoiceHeaders);
                if (invoiceDate != null && !invoiceDate.isBlank()) {
                    LocalDate date = parseInvoiceDate(invoiceDate);
                    if (date != null) {
                        lastOrderDateMap.merge(key, date, (existing, newDate) -> newDate.isAfter(existing) ? newDate : existing);
                    }
                }
            }
        }
        
        // Convert LocalDate to String (DD-MMM-YY format, e.g., "24-JAN-24")
        Map<String, String> result = new java.util.HashMap<>();
        for (Map.Entry<String, LocalDate> entry : lastOrderDateMap.entrySet()) {
            LocalDate date = entry.getValue();
            String monthAbbr = date.getMonth().toString().substring(0, 3);
            int year = date.getYear() % 100; // Get last 2 digits of year
            result.put(entry.getKey(), String.format("%02d-%s-%02d", date.getDayOfMonth(), monthAbbr, year));
        }
        return result;
    }

    private String extractToken(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return null;
        }
        String prefix = "Bearer ";
        if (authHeader.startsWith(prefix)) {
            return authHeader.substring(prefix.length()).trim();
        }
        return authHeader.trim();
    }

    /**
     * Calculate similarity between two customer names using token-based matching.
     * Returns a value between 0.0 (no match) and 1.0 (exact match).
     */
    private double calculateCustomerSimilarity(String name1, String name2) {
        if (name1 == null || name2 == null || name1.isBlank() || name2.isBlank()) {
            return 0.0;
        }

        String normalized1 = normalizeCustomer(name1);
        String normalized2 = normalizeCustomer(name2);

        if (normalized1.equals(normalized2)) {
            return 1.0;
        }

        List<String> tokens1 = tokenize(normalized1);
        List<String> tokens2 = tokenize(normalized2);

        if (tokens1.isEmpty() || tokens2.isEmpty()) {
            return 0.0;
        }

        // Calculate Jaccard similarity (intersection over union)
        Set<String> set1 = new HashSet<>(tokens1);
        Set<String> set2 = new HashSet<>(tokens2);

        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);

        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);

        if (union.isEmpty()) {
            return 0.0;
        }

        double jaccardSimilarity = (double) intersection.size() / union.size();

        // Boost similarity if one name contains all tokens of the other (subset relationship)
        if (set1.containsAll(set2) || set2.containsAll(set1)) {
            jaccardSimilarity = Math.max(jaccardSimilarity, 0.8);
        }

        return jaccardSimilarity;
    }

    /**
     * Find the best matching customer from customer_master using fuzzy matching.
     * Returns the matching PaymentDateOverride if similarity > threshold, null otherwise.
     */
    private PaymentDateOverride findFuzzyMatch(String customerDisplayName, String customerKey,
                                               Map<String, PaymentDateOverride> allOverrides,
                                               double similarityThreshold) {
        // First try exact match
        PaymentDateOverride exactMatch = allOverrides.get(customerKey);
        if (exactMatch != null) {
            return exactMatch;
        }

        // Try fuzzy matching
        PaymentDateOverride bestMatch = null;
        double bestSimilarity = 0.0;

        for (PaymentDateOverride override : allOverrides.values()) {
            double similarity = calculateCustomerSimilarity(customerDisplayName, override.customerName());
            if (similarity > bestSimilarity && similarity >= similarityThreshold) {
                bestSimilarity = similarity;
                bestMatch = override;
            }
        }

        return bestMatch;
    }

    @GetMapping("/sales-visualization")
    public ResponseEntity<SalesVisualizationResponse> salesVisualization(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "month", required = false) Integer month,
            @RequestParam(value = "quarter", required = false) Integer quarter
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessSalesVisualization(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        DetailedSalesInvoicesUpload latest = detailedSalesInvoicesUploadRepository.findTopByOrderByUploadedAtDesc();
        if (latest == null) {
            return ResponseEntity.ok(new SalesVisualizationResponse(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0
            ));
        }

        UploadedExcelFile file = latest.file();
        List<SalesDataPoint> salesDataPoints = new ArrayList<>();
        Map<String, Double> customerRevenue = new java.util.HashMap<>();
        Map<String, Integer> paymentStatusCounts = new java.util.HashMap<>();
        Map<String, Double> ageingBucketAmounts = new java.util.HashMap<>();
        Map<String, Double> monthlyRevenue = new java.util.HashMap<>();
        
        double totalRevenue = 0.0;
        double totalReceived = 0.0;
        double totalOutstanding = 0.0;
        int totalInvoices = 0;

        for (UploadedExcelSheet sheet : file.sheets()) {
            List<String> customerHeaders = sheet.headers().stream()
                    .filter(this::isCustomerHeader)
                    .toList();
            List<String> invoiceHeaders = sheet.headers().stream()
                    .filter(this::isInvoiceDateHeader)
                    .toList();
            List<String> receivedHeaders = sheet.headers().stream()
                    .filter(this::isReceivedHeader)
                    .toList();
            List<String> dueHeaders = sheet.headers().stream()
                    .filter(this::isCurrentDueHeader)
                    .toList();

            for (Map<String, String> row : sheet.rows()) {
                String customer = firstNonBlank(row, customerHeaders);
                if (customer == null || customer.isBlank()) {
                    continue;
                }

                String invoiceDate = firstNonBlank(row, invoiceHeaders);
                double receivedAmount = sumAmounts(row, receivedHeaders);
                double currentDue = sumAmounts(row, dueHeaders);
                double totalAmount = receivedAmount + currentDue;

                if (totalAmount == 0) {
                    continue;
                }

                LocalDate invoiceLocalDate = parseInvoiceDate(invoiceDate);
                if (invoiceLocalDate == null) {
                    continue;
                }

                // Apply filters
                if (year != null && invoiceLocalDate.getYear() != year) {
                    continue;
                }
                if (month != null && invoiceLocalDate.getMonthValue() != month) {
                    continue;
                }
                if (quarter != null) {
                    int invoiceQuarter = (invoiceLocalDate.getMonthValue() - 1) / 3 + 1;
                    if (invoiceQuarter != quarter) {
                        continue;
                    }
                }

                totalInvoices++;
                totalRevenue += totalAmount;
                totalReceived += receivedAmount;
                totalOutstanding += currentDue;

                // Customer revenue
                customerRevenue.merge(customer, totalAmount, Double::sum);

                // Payment status
                String status;
                if (currentDue <= 0.01) {
                    status = "Paid";
                } else if (receivedAmount <= 0.01) {
                    status = "Unpaid";
                } else {
                    status = "Partial";
                }
                paymentStatusCounts.merge(status, 1, Integer::sum);

                // Ageing buckets - only compute when there's an amount due
                Integer ageingDays = (currentDue > 0.01) ? computeAgeingDays(invoiceDate) : null;
                if (ageingDays != null) {
                    String bucket;
                    if (ageingDays >= 1 && ageingDays <= 45) {
                        bucket = "1-45 Days";
                    } else if (ageingDays >= 46 && ageingDays <= 85) {
                        bucket = "46-85 Days";
                    } else {
                        bucket = "90+ Days";
                    }
                    ageingBucketAmounts.merge(bucket, currentDue, Double::sum);
                }

                // Monthly revenue
                String monthKey = invoiceLocalDate.getYear() + "-" + 
                        String.format("%02d", invoiceLocalDate.getMonthValue());
                monthlyRevenue.merge(monthKey, totalAmount, Double::sum);

                salesDataPoints.add(new SalesDataPoint(
                        invoiceDate,
                        customer,
                        totalAmount,
                        receivedAmount,
                        currentDue,
                        ageingDays
                ));
            }
        }

        // Build monthly trends (last 12 months)
        List<MonthlyTrendData> monthlyTrends = new ArrayList<>();
        LocalDate now = LocalDate.now(ZoneId.systemDefault());
        for (int i = 11; i >= 0; i--) {
            LocalDate monthDate = now.minusMonths(i);
            String monthKey = monthDate.getYear() + "-" + 
                    String.format("%02d", monthDate.getMonthValue());
            double revenue = monthlyRevenue.getOrDefault(monthKey, 0.0);
            String label = monthDate.getMonth().toString().substring(0, 3) + " " + monthDate.getYear();
            monthlyTrends.add(new MonthlyTrendData(label, revenue));
        }

        // Top customers (top 10)
        List<CustomerRevenueData> topCustomers = customerRevenue.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(10)
                .map(e -> new CustomerRevenueData(e.getKey(), e.getValue()))
                .collect(Collectors.toList());

        // Payment status distribution
        List<PaymentStatusData> paymentStatusData = paymentStatusCounts.entrySet().stream()
                .map(e -> new PaymentStatusData(e.getKey(), e.getValue()))
                .collect(Collectors.toList());

        // Ageing bucket amounts
        List<AgeingBucketData> ageingBucketData = ageingBucketAmounts.entrySet().stream()
                .map(e -> new AgeingBucketData(e.getKey(), e.getValue()))
                .collect(Collectors.toList());

        return ResponseEntity.ok(new SalesVisualizationResponse(
                monthlyTrends,
                topCustomers,
                paymentStatusData,
                ageingBucketData,
                List.of(),
                totalRevenue,
                totalReceived,
                totalOutstanding,
                totalReceived / (totalRevenue > 0 ? totalRevenue : 1) * 100,
                totalInvoices
        ));
    }

    private record SalesDataPoint(
            String invoiceDate,
            String customer,
            double totalAmount,
            double receivedAmount,
            double currentDue,
            Integer ageingDays
    ) {}

    private record MonthlyTrendData(String month, double revenue) {}

    private record CustomerRevenueData(String customer, double revenue) {}

    private record PaymentStatusData(String status, int count) {}

    private record AgeingBucketData(String bucket, double amount) {}

    private record SalesVisualizationResponse(
            List<MonthlyTrendData> monthlyTrends,
            List<CustomerRevenueData> topCustomers,
            List<PaymentStatusData> paymentStatusDistribution,
            List<AgeingBucketData> ageingBucketAmounts,
            List<Object> additionalMetrics,
            double totalRevenue,
            double totalReceived,
            double totalOutstanding,
            double collectionRate,
            int totalInvoices
    ) {}

}

