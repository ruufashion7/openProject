package org.example.api;

import org.example.auth.AuthSessionService;
import org.example.auth.SessionInfo;
import org.example.auth.User;
import org.example.auth.UserService;
import org.example.payment.CustomerNote;
import org.example.payment.PaymentDateOverride;
import org.example.payment.PaymentDateOverrideRepository;
import org.example.security.InputValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST controller for managing customer notes.
 * Notes are stored in the customer_master collection (PaymentDateOverride) as embedded documents.
 */
@RestController
@RequestMapping("/api/customer-notes")
public class CustomerNotesController {
    private static final Logger logger = LoggerFactory.getLogger(CustomerNotesController.class);
    
    private final PaymentDateOverrideRepository paymentDateOverrideRepository;
    private final AuthSessionService authSessionService;
    private final InputValidationService inputValidationService;
    private final UserService userService;

    public CustomerNotesController(
            PaymentDateOverrideRepository paymentDateOverrideRepository,
            AuthSessionService authSessionService,
            InputValidationService inputValidationService,
            UserService userService) {
        this.paymentDateOverrideRepository = paymentDateOverrideRepository;
        this.authSessionService = authSessionService;
        this.inputValidationService = inputValidationService;
        this.userService = userService;
    }

    /**
     * Get all notes for a customer.
     * SECURITY: Customer name and phone are sent in POST body, not URL.
     */
    @PostMapping("/get")
    public ResponseEntity<List<CustomerNoteResponse>> getNotes(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody CustomerNoteSearchRequest request) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // SECURITY: Validate input
        String customerName = request.customerName() != null ? request.customerName().trim() : null;
        String phoneNumber = request.phoneNumber() != null ? request.phoneNumber().trim() : null;

        // At least one identifier must be provided
        if ((customerName == null || customerName.isEmpty()) && 
            (phoneNumber == null || phoneNumber.isEmpty())) {
            return ResponseEntity.badRequest().build();
        }

        // SECURITY: Validate lengths
        if (customerName != null && customerName.length() > 200) {
            return ResponseEntity.badRequest().build();
        }
        if (phoneNumber != null && phoneNumber.length() > 20) {
            return ResponseEntity.badRequest().build();
        }

        // Find PaymentDateOverride by customerKey or phoneNumber
        PaymentDateOverride paymentDateOverride = findPaymentDateOverride(customerName, phoneNumber);
        
        logger.info("Getting notes for customer: customerName={}, phoneNumber={}, found={}, notesCount={}", 
                customerName, phoneNumber, 
                paymentDateOverride != null, 
                paymentDateOverride != null && paymentDateOverride.notes() != null ? paymentDateOverride.notes().size() : 0);
        
        if (paymentDateOverride == null || paymentDateOverride.notes() == null || paymentDateOverride.notes().isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        // Convert embedded notes to response DTOs, sorted by createdAt descending
        List<CustomerNoteResponse> responses = paymentDateOverride.notes().stream()
                .sorted(Comparator.comparing(CustomerNote::createdAt).reversed())
                .map(note -> new CustomerNoteResponse(
                        note.id(),
                        paymentDateOverride.customerName(),
                        paymentDateOverride.phoneNumber(),
                        note.note(),
                        note.createdBy(),
                        note.createdAt(),
                        note.updatedAt(),
                        note.updatedBy()
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    /**
     * Create a new note for a customer.
     * SECURITY: All data sent in POST body.
     */
    @PostMapping("/create")
    public ResponseEntity<CustomerNoteResponse> createNote(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody CreateCustomerNoteRequest request) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // SECURITY: Validate input
        String customerName = request.customerName() != null ? request.customerName().trim() : null;
        String phoneNumber = request.phoneNumber() != null ? request.phoneNumber().trim() : null;
        String noteContent = request.note() != null ? request.note().trim() : null;

        // At least one identifier must be provided
        if ((customerName == null || customerName.isEmpty()) && 
            (phoneNumber == null || phoneNumber.isEmpty())) {
            return ResponseEntity.badRequest().build();
        }

        // Note content is required
        if (noteContent == null || noteContent.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        // SECURITY: Validate lengths
        if (customerName != null && customerName.length() > 200) {
            return ResponseEntity.badRequest().build();
        }
        if (phoneNumber != null && phoneNumber.length() > 20) {
            return ResponseEntity.badRequest().build();
        }
        if (noteContent.length() > 5000) { // Max 5000 characters for note
            return ResponseEntity.badRequest().build();
        }

        // Get username from user service
        String username = getUserName(session);
        Instant now = Instant.now();
        
        // Find or create PaymentDateOverride
        PaymentDateOverride paymentDateOverride = findOrCreatePaymentDateOverride(customerName, phoneNumber);
        
        logger.info("Creating note for customer: customerName={}, phoneNumber={}, customerKey={}", 
                customerName, phoneNumber, paymentDateOverride.customerKey());
        
        // Create new note
        CustomerNote newNote = new CustomerNote(
                UUID.randomUUID().toString(),
                noteContent,
                username,
                now,
                now,
                username
        );
        
        // Add note to the list - ensure we have a mutable list
        List<CustomerNote> notes = new ArrayList<>();
        if (paymentDateOverride.notes() != null && !paymentDateOverride.notes().isEmpty()) {
            notes.addAll(paymentDateOverride.notes());
        }
        notes.add(newNote);
        
        logger.info("Adding note. Total notes before save: {}", notes.size());
        
        // Update PaymentDateOverride with new notes list
        PaymentDateOverride updated = new PaymentDateOverride(
                paymentDateOverride.id(),
                paymentDateOverride.customerKey(),
                paymentDateOverride.customerName() != null ? paymentDateOverride.customerName() : customerName,
                paymentDateOverride.nextPaymentDate(),
                paymentDateOverride.phoneNumber() != null ? paymentDateOverride.phoneNumber() : phoneNumber,
                paymentDateOverride.whatsAppStatus(),
                paymentDateOverride.customerCategory(),
                paymentDateOverride.active(),
                paymentDateOverride.needsFollowUp(),
                paymentDateOverride.address(),
                paymentDateOverride.place(),
                paymentDateOverride.latitude(),
                paymentDateOverride.longitude(),
                notes,
                now
        );
        
        PaymentDateOverride saved = paymentDateOverrideRepository.save(updated);
        logger.info("Note saved. Saved document has {} notes", saved.notes() != null ? saved.notes().size() : 0);

        CustomerNoteResponse response = new CustomerNoteResponse(
                newNote.id(),
                updated.customerName(),
                updated.phoneNumber(),
                newNote.note(),
                newNote.createdBy(),
                newNote.createdAt(),
                newNote.updatedAt(),
                newNote.updatedBy()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Update an existing note.
     * SECURITY: Note ID and content sent in POST body.
     */
    @PostMapping("/update")
    public ResponseEntity<CustomerNoteResponse> updateNote(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody UpdateCustomerNoteRequest request) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // SECURITY: Validate note ID
        String noteId = request.noteId();
        if (noteId == null || noteId.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (!inputValidationService.isValidId(noteId)) {
            return ResponseEntity.badRequest().build();
        }

        String noteContent = request.note() != null ? request.note().trim() : null;

        // Note content is required
        if (noteContent == null || noteContent.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        // SECURITY: Validate length
        if (noteContent.length() > 5000) {
            return ResponseEntity.badRequest().build();
        }

        // Find PaymentDateOverride containing this note
        PaymentDateOverride paymentDateOverride = findPaymentDateOverrideByNoteId(noteId);
        if (paymentDateOverride == null) {
            return ResponseEntity.notFound().build();
        }

        // Update the note in the list
        List<CustomerNote> updatedNotes = paymentDateOverride.notes().stream()
                .map(note -> {
                    if (note.id().equals(noteId)) {
                        return new CustomerNote(
                                note.id(),
                                noteContent,
                                note.createdBy(),
                                note.createdAt(),
                                Instant.now(),
                                getUserName(session)
                        );
                    }
                    return note;
                })
                .collect(Collectors.toList());
        
        // Update PaymentDateOverride
        PaymentDateOverride updated = new PaymentDateOverride(
                paymentDateOverride.id(),
                paymentDateOverride.customerKey(),
                paymentDateOverride.customerName(),
                paymentDateOverride.nextPaymentDate(),
                paymentDateOverride.phoneNumber(),
                paymentDateOverride.whatsAppStatus(),
                paymentDateOverride.customerCategory(),
                paymentDateOverride.active(),
                paymentDateOverride.needsFollowUp(),
                paymentDateOverride.address(),
                paymentDateOverride.place(),
                paymentDateOverride.latitude(),
                paymentDateOverride.longitude(),
                updatedNotes,
                Instant.now()
        );
        
        paymentDateOverrideRepository.save(updated);

        // Find the updated note for response
        CustomerNote updatedNote = updatedNotes.stream()
                .filter(note -> note.id().equals(noteId))
                .findFirst()
                .orElseThrow();

        CustomerNoteResponse response = new CustomerNoteResponse(
                updatedNote.id(),
                updated.customerName(),
                updated.phoneNumber(),
                updatedNote.note(),
                updatedNote.createdBy(),
                updatedNote.createdAt(),
                updatedNote.updatedAt(),
                updatedNote.updatedBy()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Delete a note.
     * SECURITY: Note ID sent in POST body.
     */
    @PostMapping("/delete")
    public ResponseEntity<Void> deleteNote(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody DeleteCustomerNoteRequest request) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // SECURITY: Validate note ID
        String noteId = request.noteId();
        if (noteId == null || noteId.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (!inputValidationService.isValidId(noteId)) {
            return ResponseEntity.badRequest().build();
        }

        // Find PaymentDateOverride containing this note
        PaymentDateOverride paymentDateOverride = findPaymentDateOverrideByNoteId(noteId);
        if (paymentDateOverride == null) {
            return ResponseEntity.notFound().build();
        }

        // Remove the note from the list
        List<CustomerNote> updatedNotes = paymentDateOverride.notes().stream()
                .filter(note -> !note.id().equals(noteId))
                .collect(Collectors.toList());
        
        // Update PaymentDateOverride
        PaymentDateOverride updated = new PaymentDateOverride(
                paymentDateOverride.id(),
                paymentDateOverride.customerKey(),
                paymentDateOverride.customerName(),
                paymentDateOverride.nextPaymentDate(),
                paymentDateOverride.phoneNumber(),
                paymentDateOverride.whatsAppStatus(),
                paymentDateOverride.customerCategory(),
                paymentDateOverride.active(),
                paymentDateOverride.needsFollowUp(),
                paymentDateOverride.address(),
                paymentDateOverride.place(),
                paymentDateOverride.latitude(),
                paymentDateOverride.longitude(),
                updatedNotes,
                Instant.now()
        );
        
        paymentDateOverrideRepository.save(updated);

        return ResponseEntity.noContent().build();
    }

    /**
     * Find PaymentDateOverride by customer name or phone number.
     */
    private PaymentDateOverride findPaymentDateOverride(String customerName, String phoneNumber) {
        // Try by customerKey first if customerName is provided
        if (customerName != null && !customerName.isEmpty()) {
            String customerKey = normalizeCustomer(customerName);
            logger.debug("Searching by customerKey: {}", customerKey);
            Optional<PaymentDateOverride> byKey = paymentDateOverrideRepository.findByCustomerKey(customerKey);
            if (byKey.isPresent()) {
                logger.debug("Found PaymentDateOverride by customerKey: {}", customerKey);
                return byKey.get();
            }
        }
        
        // Try by phone number
        if (phoneNumber != null && !phoneNumber.isEmpty()) {
            logger.debug("Searching by phoneNumber: {}", phoneNumber);
            // Search all PaymentDateOverride records for matching phone number
            // Note: This is not efficient for large datasets, but necessary since we don't have an index on phoneNumber
            List<PaymentDateOverride> all = paymentDateOverrideRepository.findAll();
            PaymentDateOverride found = all.stream()
                    .filter(pdo -> pdo.phoneNumber() != null && phoneNumber.equals(pdo.phoneNumber().trim()))
                    .findFirst()
                    .orElse(null);
            if (found != null) {
                logger.debug("Found PaymentDateOverride by phoneNumber: {}", phoneNumber);
            }
            return found;
        }
        
        logger.debug("No PaymentDateOverride found for customerName={}, phoneNumber={}", customerName, phoneNumber);
        return null;
    }

    /**
     * Find PaymentDateOverride containing a specific note ID.
     * Uses efficient MongoDB query instead of loading all documents.
     */
    private PaymentDateOverride findPaymentDateOverrideByNoteId(String noteId) {
        return paymentDateOverrideRepository.findByNotesId(noteId).orElse(null);
    }

    /**
     * Find or create PaymentDateOverride for a customer.
     */
    private PaymentDateOverride findOrCreatePaymentDateOverride(String customerName, String phoneNumber) {
        PaymentDateOverride existing = findPaymentDateOverride(customerName, phoneNumber);
        
        if (existing != null) {
            logger.info("Using existing PaymentDateOverride: id={}, customerKey={}", existing.id(), existing.customerKey());
            return existing;
        }
        
        // Create new PaymentDateOverride
        String customerKey = customerName != null && !customerName.isEmpty() 
                ? normalizeCustomer(customerName) 
                : (phoneNumber != null ? phoneNumber : UUID.randomUUID().toString());
        
        logger.info("Creating new PaymentDateOverride: customerKey={}, customerName={}, phoneNumber={}", 
                customerKey, customerName, phoneNumber);
        
        PaymentDateOverride newOverride = new PaymentDateOverride(
                null, // Let MongoDB generate ID
                customerKey,
                customerName,
                null, // nextPaymentDate
                phoneNumber,
                null, // whatsAppStatus
                null, // customerCategory
                true, // active
                false, // needsFollowUp
                null, // address
                null, // place
                null, // latitude
                null, // longitude
                new ArrayList<CustomerNote>(), // notes (empty initially)
                Instant.now()
        );
        
        PaymentDateOverride saved = paymentDateOverrideRepository.save(newOverride);
        logger.info("Created new PaymentDateOverride: id={}, customerKey={}", saved.id(), saved.customerKey());
        return saved;
    }

    /**
     * Normalize customer name to create a consistent customerKey.
     * This matches the logic used in AnalyticsController.
     */
    private String normalizeCustomer(String customerName) {
        if (customerName == null || customerName.isEmpty()) {
            return "";
        }
        return customerName.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private String extractToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        return authHeader.substring(7);
    }

    private String getUserName(SessionInfo session) {
        if (session.userId() != null) {
            Optional<User> userOpt = userService.getUserById(session.userId());
            if (userOpt.isPresent()) {
                return userOpt.get().getUsername();
            }
        }
        // Fallback to displayName if username not available
        return session.displayName();
    }

    // Request/Response DTOs

    public record CustomerNoteSearchRequest(
            String customerName,
            String phoneNumber
    ) {}

    public record CreateCustomerNoteRequest(
            String customerName,
            String phoneNumber,
            String note
    ) {}

    public record UpdateCustomerNoteRequest(
            String noteId,
            String note
    ) {}

    public record DeleteCustomerNoteRequest(
            String noteId
    ) {}

    public record CustomerNoteResponse(
            String id,
            String customerName,
            String phoneNumber,
            String note,
            String createdBy,
            Instant createdAt,
            Instant updatedAt,
            String updatedBy
    ) {}
}
