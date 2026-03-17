package org.example.api;

import org.example.auth.AuthSessionService;
import org.example.auth.SessionInfo;
import org.example.ratelist.RateListEntry;
import org.example.ratelist.RateListEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFDataValidation;
import org.apache.poi.xssf.usermodel.XSSFDataValidationConstraint;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/rate-list")
public class RateListController {
    private static final Logger logger = LoggerFactory.getLogger(RateListController.class);
    
    private final AuthSessionService authSessionService;
    private final RateListEntryRepository rateListEntryRepository;

    public RateListController(AuthSessionService authSessionService,
                             RateListEntryRepository rateListEntryRepository) {
        this.authSessionService = authSessionService;
        this.rateListEntryRepository = rateListEntryRepository;
    }

    @GetMapping
    public ResponseEntity<List<RateListEntry>> getAllEntries(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            List<RateListEntry> entries = rateListEntryRepository.findAllByOrderByCreatedAtDesc();
            logger.debug("Fetched {} rate list entries", entries.size());
            return ResponseEntity.ok(entries);
        } catch (Exception e) {
            logger.error("Error fetching rate list entries", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping
    public ResponseEntity<?> createEntry(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> request
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Extract fields outside try block for error handling
        String date = (String) request.get("date");
        String type = (String) request.get("type");
        String productName = (String) request.get("productName");
        String size = (String) request.get("size");
        Object rateObj = request.get("rate");
        Object srNoObj = request.get("srNo");

        try {
            // Validate required fields
            if (date == null || (!date.equals("old") && !date.equals("new"))) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Invalid date. Must be 'old' or 'new'."));
            }
            if (type == null || (!type.equals("landing") && !type.equals("resale"))) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Invalid type. Must be 'landing' or 'resale'."));
            }
            if (productName == null || productName.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Product name is required."));
            }
            if (size == null || (!size.equals("80-90") && !size.equals("95-100"))) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Invalid size. Must be '80-90' or '95-100'."));
            }
            
            Double rate;
            if (rateObj instanceof Number) {
                rate = ((Number) rateObj).doubleValue();
            } else if (rateObj instanceof String) {
                try {
                    rate = Double.parseDouble((String) rateObj);
                } catch (NumberFormatException e) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("message", "Invalid rate format. Please enter a valid number."));
                }
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Rate is required and must be a number."));
            }
            
            if (rate <= 0) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Rate must be greater than 0."));
            }

            // Trim product name
            String trimmedProductName = productName.trim();
            
            // Check for duplicate entry (same date, type, productName, and size)
            List<RateListEntry> existingEntries = rateListEntryRepository.findByDateAndTypeAndProductNameAndSize(
                    date, type, trimmedProductName, size
            );
            
            if (!existingEntries.isEmpty()) {
                logger.warn("Duplicate rate list entry attempted: date={}, type={}, productName={}, size={} (found {} existing entries) by user: {}", 
                        date, type, trimmedProductName, size, existingEntries.size(), session.displayName());
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("message", String.format(
                                "Duplicate entry found! An entry already exists for Product: '%s', Date: %s, Type: %s, Size: %s. Please update the existing entry instead.",
                                trimmedProductName, date, type, size
                        )));
            }

            // Extract and validate srNo
            Integer srNo = null;
            if (srNoObj != null) {
                if (srNoObj instanceof Number) {
                    int srNoValue = ((Number) srNoObj).intValue();
                    if (srNoValue > 0) {
                        srNo = srNoValue;
                    }
                } else if (srNoObj instanceof String) {
                    try {
                        int srNoValue = Integer.parseInt((String) srNoObj);
                        if (srNoValue > 0) {
                            srNo = srNoValue;
                        }
                    } catch (NumberFormatException e) {
                        // Invalid srNo format, will use null
                    }
                }
            }
            
            // If srNo not provided, try to get it from existing entries of the same product
            if (srNo == null) {
                List<RateListEntry> productEntries = rateListEntryRepository.findAll().stream()
                        .filter(e -> e.productName().equals(trimmedProductName))
                        .filter(e -> e.srNo() != null)
                        .toList();
                if (!productEntries.isEmpty()) {
                    srNo = productEntries.get(0).srNo();
                }
            }

            RateListEntry entry = new RateListEntry(
                    null,
                    date,
                    type,
                    trimmedProductName,
                    size,
                    rate,
                    srNo,
                    Instant.now()
            );

            RateListEntry saved = rateListEntryRepository.save(entry);
            logger.info("Created rate list entry: {} by user: {}", saved.id(), session.displayName());
            return ResponseEntity.ok(saved);
        } catch (org.springframework.dao.IncorrectResultSizeDataAccessException e) {
            logger.error("Duplicate entries found in database for: date={}, type={}, productName={}, size={}", 
                    date, type, productName, size, e);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", String.format(
                            "Duplicate entry found! Multiple entries already exist for Product: '%s', Date: %s, Type: %s, Size: %s. Please clean up duplicates or update existing entries.",
                            productName != null ? productName.trim() : "", date, type, size
                    )));
        } catch (Exception e) {
            logger.error("Error creating rate list entry", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "An error occurred while saving the entry. Please try again or contact support."));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<RateListEntry> updateEntry(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String id,
            @RequestBody Map<String, Object> request
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // SECURITY: Validate path variable
        if (id == null || id.isBlank() || id.length() > 100) {
            return ResponseEntity.badRequest().build();
        }

        try {
            RateListEntry existing = rateListEntryRepository.findById(id).orElse(null);
            if (existing == null) {
                return ResponseEntity.notFound().build();
            }

            // Validate and extract fields
            String date = (String) request.get("date");
            String type = (String) request.get("type");
            String productName = (String) request.get("productName");
            String size = (String) request.get("size");
            Object rateObj = request.get("rate");
            Object srNoObj = request.get("srNo");
            
            // Validate required fields
            if (date == null || (!date.equals("old") && !date.equals("new"))) {
                return ResponseEntity.badRequest().build();
            }
            if (type == null || (!type.equals("landing") && !type.equals("resale"))) {
                return ResponseEntity.badRequest().build();
            }
            if (productName == null || productName.trim().isEmpty()) {
                return ResponseEntity.badRequest().build();
            }
            if (size == null || (!size.equals("80-90") && !size.equals("95-100"))) {
                return ResponseEntity.badRequest().build();
            }
            
            Double rate;
            if (rateObj instanceof Number) {
                rate = ((Number) rateObj).doubleValue();
            } else if (rateObj instanceof String) {
                try {
                    rate = Double.parseDouble((String) rateObj);
                } catch (NumberFormatException e) {
                    return ResponseEntity.badRequest().build();
                }
            } else {
                return ResponseEntity.badRequest().build();
            }
            
            if (rate <= 0) {
                return ResponseEntity.badRequest().build();
            }

            // Extract and validate srNo
            Integer srNo = existing.srNo(); // Preserve existing srNo by default
            if (srNoObj != null) {
                if (srNoObj instanceof Number) {
                    int srNoValue = ((Number) srNoObj).intValue();
                    if (srNoValue > 0) {
                        srNo = srNoValue;
                    }
                } else if (srNoObj instanceof String) {
                    try {
                        int srNoValue = Integer.parseInt((String) srNoObj);
                        if (srNoValue > 0) {
                            srNo = srNoValue;
                        }
                    } catch (NumberFormatException e) {
                        // Invalid srNo format, keep existing
                    }
                }
            }
            
            // If srNo not provided, try to get it from existing entries of the same product
            if (srNo == null) {
                List<RateListEntry> productEntries = rateListEntryRepository.findAll().stream()
                        .filter(e -> e.productName().equals(productName.trim()))
                        .filter(e -> e.srNo() != null)
                        .toList();
                if (!productEntries.isEmpty()) {
                    srNo = productEntries.get(0).srNo();
                }
            }

            RateListEntry updated = new RateListEntry(
                    id,
                    date,
                    type,
                    productName.trim(),
                    size,
                    rate,
                    srNo,
                    existing.createdAt() // Preserve original creation date
            );

            RateListEntry saved = rateListEntryRepository.save(updated);
            logger.info("Updated rate list entry: {} by user: {}", saved.id(), session.displayName());
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            logger.error("Error updating rate list entry: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEntry(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String id
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // SECURITY: Validate path variable
        if (id == null || id.isBlank() || id.length() > 100) {
            return ResponseEntity.badRequest().build();
        }

        try {
            if (!rateListEntryRepository.existsById(id)) {
                return ResponseEntity.notFound().build();
            }
            
            rateListEntryRepository.deleteById(id);
            logger.info("Deleted rate list entry: {} by user: {}", id, session.displayName());
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            logger.error("Error deleting rate list entry: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> downloadTemplate(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            XSSFWorkbook workbook = new XSSFWorkbook();
            XSSFSheet sheet = workbook.createSheet("Rate List Template");
            
            // Use the same predefined product list as the UI
            List<String> predefinedProducts = new ArrayList<>(List.of(
                "rupa jon volt trunk", "rupa macroman", "rupa expando", "rupa hunk trunk",
                "rupa jon vest wht rn", "rupa jon vest wht rns", "rupa jon vest colour rn",
                "rupa frontline vest wht rn", "rupa frontline xing wht", "rupa frontline xing black",
                "rupa hunk gym vest #1061", "rupa hunk gym vest #072", "macho yellow trunk",
                "macho metro red cut", "macho green mini trunk", "macho blue intro long trunk",
                "macho metro vest red", "macho parker lining vest green", "speed trunk",
                "speed vest", "lux venus trunk", "lux venus trunk 95/100", "lux venus vest",
                "amul comfy fcd", "amul comfy rn wht 2/10", "amul comfy trunk",
                "amul comfy plain cycling short", "amul marvel kids trunk m9023",
                "amul marvel kids vest rn m9001", "amul sporto intro trunk", "amul sporto plain long trunk",
                "amul sporto smart cut brief", "amul sporto plain mini trunk",
                "amul sporto gym vest 111/222 (80/85/90/95/100)"
            ));
            
            // Get additional product names from database that are not in predefined list
            List<String> dbProducts = rateListEntryRepository.findAll().stream()
                    .map(RateListEntry::productName)
                    .distinct()
                    .filter(name -> !predefinedProducts.contains(name))
                    .sorted()
                    .toList();
            
            // Combine predefined products with database products (predefined first, then DB products)
            List<String> productNames = new ArrayList<>(predefinedProducts);
            productNames.addAll(dbProducts);
            
            // Create header row - matching UI format
            Row headerRow = sheet.createRow(0);
            String[] headers = {"Sr No", "Date (old/new)", "Product Name", "Size (80-90/95-100)", "Landing Rate", "Resale Rate"};
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setFontHeightInPoints((short) 12);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }
            
            // Add example row
            Row exampleRow = sheet.createRow(1);
            exampleRow.createCell(0).setCellValue("1");
            exampleRow.createCell(1).setCellValue("new");
            exampleRow.createCell(2).setCellValue(productNames.isEmpty() ? "rupa jon volt trunk" : productNames.get(0));
            exampleRow.createCell(3).setCellValue("80-90");
            exampleRow.createCell(4).setCellValue("100.00");
            exampleRow.createCell(5).setCellValue("120.00");
            
            // Style example row
            CellStyle exampleStyle = workbook.createCellStyle();
            exampleStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            exampleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font exampleFont = workbook.createFont();
            exampleFont.setItalic(true);
            exampleFont.setFontHeightInPoints((short) 10);
            exampleStyle.setFont(exampleFont);
            for (int i = 0; i < headers.length; i++) {
                exampleRow.getCell(i).setCellStyle(exampleStyle);
            }
            
            // Create a hidden sheet for product names (to avoid 255 character limit)
            Sheet productSheet = workbook.createSheet("Products");
            productSheet.setColumnHidden(0, true);
            for (int i = 0; i < productNames.size(); i++) {
                Row row = productSheet.createRow(i);
                row.createCell(0).setCellValue(productNames.get(i));
            }
            
            // Create data validation helper
            XSSFDataValidationHelper dvHelper = new XSSFDataValidationHelper(sheet);
            
            // Date column dropdown (Column B, starting from row 2)
            XSSFDataValidationConstraint dateConstraint = (XSSFDataValidationConstraint) dvHelper.createExplicitListConstraint(new String[]{"old", "new"});
            CellRangeAddressList dateAddressList = new CellRangeAddressList(1, 10000, 1, 1);
            XSSFDataValidation dateValidation = (XSSFDataValidation) dvHelper.createValidation(dateConstraint, dateAddressList);
            dateValidation.setShowErrorBox(true);
            dateValidation.setErrorStyle(DataValidation.ErrorStyle.STOP);
            dateValidation.createErrorBox("Invalid Value", "Please select 'old' or 'new'");
            sheet.addValidationData(dateValidation);
            
            // Product Name column dropdown (Column C, starting from row 2) - using formula reference to avoid 255 char limit
            String productFormula = "Products!$A$1:$A$" + productNames.size();
            XSSFDataValidationConstraint productConstraint = (XSSFDataValidationConstraint) dvHelper.createFormulaListConstraint(productFormula);
            CellRangeAddressList productAddressList = new CellRangeAddressList(1, 10000, 2, 2);
            XSSFDataValidation productValidation = (XSSFDataValidation) dvHelper.createValidation(productConstraint, productAddressList);
            productValidation.setShowErrorBox(true);
            productValidation.setErrorStyle(DataValidation.ErrorStyle.STOP);
            productValidation.createErrorBox("Invalid Value", "Please select a product from the dropdown");
            sheet.addValidationData(productValidation);
            
            // Size column dropdown (Column D, starting from row 2)
            XSSFDataValidationConstraint sizeConstraint = (XSSFDataValidationConstraint) dvHelper.createExplicitListConstraint(new String[]{"80-90", "95-100"});
            CellRangeAddressList sizeAddressList = new CellRangeAddressList(1, 10000, 3, 3);
            XSSFDataValidation sizeValidation = (XSSFDataValidation) dvHelper.createValidation(sizeConstraint, sizeAddressList);
            sizeValidation.setShowErrorBox(true);
            sizeValidation.setErrorStyle(DataValidation.ErrorStyle.STOP);
            sizeValidation.createErrorBox("Invalid Value", "Please select '80-90' or '95-100'");
            sheet.addValidationData(sizeValidation);
            
            // Auto-size columns
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
            
            // Add watermark to header/footer
            Header header = sheet.getHeader();
            header.setCenter("RUU FASHION");
            Footer footer = sheet.getFooter();
            footer.setCenter("RUU FASHION");
            
            // Write to byte array
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            workbook.close();
            
            byte[] bytes = outputStream.toByteArray();
            
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            responseHeaders.setContentDispositionFormData("attachment", "rate_list_template.xlsx");
            
            logger.info("Template downloaded by user: {}", session.displayName());
            return ResponseEntity.ok()
                    .headers(responseHeaders)
                    .body(bytes);
        } catch (Exception e) {
            logger.error("Error generating template", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/bulk-upload")
    public ResponseEntity<Map<String, Object>> bulkUpload(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam("file") MultipartFile file
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            // Validate file
            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "File is empty"));
            }
            
            String fileName = file.getOriginalFilename();
            if (fileName == null || !fileName.toLowerCase().endsWith(".xlsx")) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "Invalid file type. Please upload .xlsx files only."));
            }
            
            // Parse and validate Excel file
            List<String> validationErrors = new ArrayList<>();
            List<RateListEntry> validEntries = new ArrayList<>();
            
            // Track entries within the upload to detect duplicates within the file itself
            Set<String> uploadEntryKeys = new HashSet<>();
            
            // Get all existing entries to check against database duplicates
            List<RateListEntry> allExistingEntries = rateListEntryRepository.findAll();
            Set<String> existingEntryKeys = new HashSet<>();
            for (RateListEntry existing : allExistingEntries) {
                String key = String.format("%s|%s|%s|%s", 
                        existing.date(), existing.type(), existing.productName().trim().toLowerCase(), existing.size());
                existingEntryKeys.add(key);
            }
            
            try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
                Sheet sheet = workbook.getSheetAt(0);
                
                // Validate header row
                Row headerRow = sheet.getRow(0);
                if (headerRow == null) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("success", false, "message", "Excel file is empty or invalid format."));
                }
                
                // Expected headers - matching UI format
                String[] expectedHeaders = {"Sr No", "Date (old/new)", "Product Name", "Size (80-90/95-100)", "Landing Rate", "Resale Rate"};
                for (int i = 0; i < expectedHeaders.length; i++) {
                    Cell cell = headerRow.getCell(i);
                    String headerValue = cell != null ? getCellValueAsString(cell) : "";
                    if (!headerValue.equalsIgnoreCase(expectedHeaders[i])) {
                        validationErrors.add(String.format("Invalid header at column %d. Expected: '%s', Found: '%s'", 
                                i + 1, expectedHeaders[i], headerValue));
                    }
                }
                
                if (!validationErrors.isEmpty()) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("success", false, "message", "Invalid Excel format.", "errors", validationErrors));
                }
                
                // Process data rows
                DataFormatter formatter = new DataFormatter();
                int rowNum = 1; // Start from row 2 (after header)
                
                // Skip example row if present (row 1)
                int startRow = 1;
                Row firstDataRow = sheet.getRow(1);
                if (firstDataRow != null) {
                    // Check second column (Date column) for example values
                    Cell dateCell = firstDataRow.getCell(1);
                    String dateCellValue = dateCell != null ? getCellValueAsString(dateCell).trim().toLowerCase() : "";
                    // If first row looks like an example (contains "new" or "old"), skip it
                    if (dateCellValue.equals("new") || dateCellValue.equals("old")) {
                        startRow = 2; // Skip example row
                    } else {
                        startRow = 1; // Still start from row 1 if no example
                    }
                }
                
                for (int i = startRow; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) {
                        continue;
                    }
                    
                    // Check if row is empty (skip Sr No column for empty check)
                    boolean isEmpty = true;
                    for (int j = 1; j < expectedHeaders.length; j++) {
                        Cell cell = row.getCell(j);
                        if (cell != null && !formatter.formatCellValue(cell).trim().isEmpty()) {
                            isEmpty = false;
                            break;
                        }
                    }
                    if (isEmpty) {
                        continue;
                    }
                    
                    rowNum++;
                    List<String> rowErrors = new ArrayList<>();
                    
                    // Read cells - new format: Sr No, Date, Product Name, Size, Landing Rate, Resale Rate
                    String srNoStr = getCellValueAsString(row.getCell(0)).trim();
                    String date = getCellValueAsString(row.getCell(1)).trim().toLowerCase();
                    String productName = getCellValueAsString(row.getCell(2)).trim();
                    String size = getCellValueAsString(row.getCell(3)).trim();
                    String landingRateStr = getCellValueAsString(row.getCell(4)).trim();
                    String resaleRateStr = getCellValueAsString(row.getCell(5)).trim();
                    
                    // Parse srNo (optional field)
                    Integer srNo = null;
                    if (!srNoStr.isEmpty()) {
                        try {
                            srNo = Integer.parseInt(srNoStr);
                            if (srNo <= 0) {
                                rowErrors.add(String.format("Row %d: Sr No must be greater than 0", rowNum));
                            }
                        } catch (NumberFormatException e) {
                            rowErrors.add(String.format("Row %d: Invalid Sr No format '%s'", rowNum, srNoStr));
                        }
                    }
                    
                    // Validate date
                    if (!date.equals("old") && !date.equals("new")) {
                        rowErrors.add(String.format("Row %d: Invalid date '%s'. Must be 'old' or 'new'", rowNum, date));
                    }
                    
                    // Validate product name
                    if (productName.isEmpty()) {
                        rowErrors.add(String.format("Row %d: Product name is required", rowNum));
                    }
                    
                    // Validate size
                    if (!size.equals("80-90") && !size.equals("95-100")) {
                        rowErrors.add(String.format("Row %d: Invalid size '%s'. Must be '80-90' or '95-100'", rowNum, size));
                    }
                    
                    // At least one rate (Landing or Resale) must be provided
                    boolean hasLandingRate = !landingRateStr.isEmpty();
                    boolean hasResaleRate = !resaleRateStr.isEmpty();
                    
                    if (!hasLandingRate && !hasResaleRate) {
                        rowErrors.add(String.format("Row %d: At least one rate (Landing or Resale) is required", rowNum));
                    }
                    
                    // Only process rates if there are no validation errors for date, size, product name
                    // Skip rate processing if basic validations failed
                    if (rowErrors.isEmpty()) {
                        // Validate Landing Rate
                        if (hasLandingRate) {
                            try {
                                double landingRate = Double.parseDouble(landingRateStr);
                                if (landingRate <= 0) {
                                    rowErrors.add(String.format("Row %d: Landing rate must be greater than 0", rowNum));
                                } else {
                                // Check for duplicate within upload
                                String entryKey = String.format("%s|landing|%s|%s", 
                                        date, productName.toLowerCase(), size);
                                if (uploadEntryKeys.contains(entryKey)) {
                                    rowErrors.add(String.format("Row %d: Duplicate entry in file - Product: '%s', Date: %s, Type: landing, Size: %s", 
                                            rowNum, productName, date, size));
                                } else {
                                    // Check for duplicate in database
                                    if (existingEntryKeys.contains(entryKey)) {
                                        rowErrors.add(String.format("Row %d: Duplicate entry already exists in database - Product: '%s', Date: %s, Type: landing, Size: %s", 
                                                rowNum, productName, date, size));
                                    } else {
                                        uploadEntryKeys.add(entryKey);
                                        // Use srNo from Excel if provided, otherwise get from existing entries of the same product
                                        Integer finalSrNo = srNo;
                                        if (finalSrNo == null) {
                                            List<RateListEntry> productEntries = allExistingEntries.stream()
                                                    .filter(e -> e.productName().equals(productName))
                                                    .filter(e -> e.srNo() != null)
                                                    .toList();
                                            if (!productEntries.isEmpty()) {
                                                finalSrNo = productEntries.get(0).srNo();
                                            }
                                        }
                                        
                                        validEntries.add(new RateListEntry(
                                                null,
                                                date,
                                                "landing",
                                                productName,
                                                size,
                                                landingRate,
                                                finalSrNo,
                                                Instant.now()
                                        ));
                                    }
                                }
                            }
                            } catch (NumberFormatException e) {
                                rowErrors.add(String.format("Row %d: Invalid landing rate format '%s'", rowNum, landingRateStr));
                            }
                        }
                        
                        // Validate Resale Rate
                        if (hasResaleRate) {
                            try {
                                double resaleRate = Double.parseDouble(resaleRateStr);
                                if (resaleRate <= 0) {
                                    rowErrors.add(String.format("Row %d: Resale rate must be greater than 0", rowNum));
                                } else {
                                // Check for duplicate within upload
                                String entryKey = String.format("%s|resale|%s|%s", 
                                        date, productName.toLowerCase(), size);
                                if (uploadEntryKeys.contains(entryKey)) {
                                    rowErrors.add(String.format("Row %d: Duplicate entry in file - Product: '%s', Date: %s, Type: resale, Size: %s", 
                                            rowNum, productName, date, size));
                                } else {
                                    // Check for duplicate in database
                                    if (existingEntryKeys.contains(entryKey)) {
                                        rowErrors.add(String.format("Row %d: Duplicate entry already exists in database - Product: '%s', Date: %s, Type: resale, Size: %s", 
                                                rowNum, productName, date, size));
                                    } else {
                                        uploadEntryKeys.add(entryKey);
                                        // Use srNo from Excel if provided, otherwise get from existing entries of the same product
                                        Integer finalSrNo = srNo;
                                        if (finalSrNo == null) {
                                            List<RateListEntry> productEntries = allExistingEntries.stream()
                                                    .filter(e -> e.productName().equals(productName))
                                                    .filter(e -> e.srNo() != null)
                                                    .toList();
                                            if (!productEntries.isEmpty()) {
                                                finalSrNo = productEntries.get(0).srNo();
                                            }
                                        }
                                        
                                        validEntries.add(new RateListEntry(
                                                null,
                                                date,
                                                "resale",
                                                productName,
                                                size,
                                                resaleRate,
                                                finalSrNo,
                                                Instant.now()
                                        ));
                                    }
                                }
                            }
                            } catch (NumberFormatException e) {
                                rowErrors.add(String.format("Row %d: Invalid resale rate format '%s'", rowNum, resaleRateStr));
                            }
                        }
                    } // End of rate processing block (only if no validation errors)
                    
                    if (!rowErrors.isEmpty()) {
                        validationErrors.addAll(rowErrors);
                    }
                }
            }
            
            if (!validationErrors.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "success", false,
                                "message", "Validation failed. Please fix the errors and try again.",
                                "errors", validationErrors,
                                "validCount", validEntries.size(),
                                "errorCount", validationErrors.size()
                        ));
            }
            
            if (validEntries.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "No valid entries found in the Excel file."));
            }
            
            // Save all valid entries
            List<RateListEntry> savedEntries = rateListEntryRepository.saveAll(validEntries);
            logger.info("Bulk uploaded {} rate list entries by user: {}", savedEntries.size(), session.displayName());
            
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", String.format("Successfully uploaded %d entries", savedEntries.size()),
                    "count", savedEntries.size()
            ));
            
        } catch (IOException e) {
            logger.error("Error processing bulk upload", e);
            // SECURITY: Don't expose internal error details to client
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Error processing file. Please check the file format and try again."));
        } catch (Exception e) {
            logger.error("Unexpected error during bulk upload", e);
            // SECURITY: Don't expose internal error details to client
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "An unexpected error occurred. Please try again or contact support."));
        }
    }
    
    private String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return "";
        }
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                } else {
                    // Format as integer if it's a whole number, otherwise as decimal
                    double numValue = cell.getNumericCellValue();
                    if (numValue == Math.floor(numValue)) {
                        return String.valueOf((long) numValue);
                    } else {
                        return String.valueOf(numValue);
                    }
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                return cell.getCellFormula();
            default:
                return "";
        }
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

    @PostMapping("/migrate-product-names")
    public ResponseEntity<Map<String, Object>> migrateProductNames(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Only admin can migrate
        if (!session.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            // Mapping of old product names to new product names
            Map<String, String> productNameMapping = Map.ofEntries(
                    Map.entry("jon volt trunk", "rupa jon volt trunk"),
                    Map.entry("macroman", "rupa macroman"),
                    Map.entry("expando", "rupa expando"),
                    Map.entry("hunk trunk", "rupa hunk trunk"),
                    Map.entry("jon vest wht rn", "rupa jon vest wht rn"),
                    Map.entry("jon vest wht rns", "rupa jon vest wht rns"),
                    Map.entry("jon vest colour rn", "rupa jon vest colour rn"),
                    Map.entry("frontline vest wht rn", "rupa frontline vest wht rn"),
                    Map.entry("frontline xing wht", "rupa frontline xing wht"),
                    Map.entry("frontline xing black", "rupa frontline xing black"),
                    Map.entry("hunk gym vest #1061", "rupa hunk gym vest #1061"),
                    Map.entry("hunk gym vest #072", "rupa hunk gym vest #072"),
                    Map.entry("metro red cut", "macho metro red cut"),
                    Map.entry("marvel kids trunk m9023", "amul marvel kids trunk m9023"),
                    Map.entry("marvel kids vest rn m9001", "amul marvel kids vest rn m9001"),
                    Map.entry("sporto intro trunk", "amul sporto intro trunk"),
                    Map.entry("sporto plain long trunk", "amul sporto plain long trunk"),
                    Map.entry("sporto smart cut brief", "amul sporto smart cut brief"),
                    Map.entry("sporto plain mini trunk", "amul sporto plain mini trunk"),
                    Map.entry("sporto gym vest 111/222 (80/85/90/95/100)", "amul sporto gym vest 111/222 (80/85/90/95/100)")
            );

            int updatedCount = 0;
            List<RateListEntry> allEntries = rateListEntryRepository.findAll();
            
            for (RateListEntry entry : allEntries) {
                String oldProductName = entry.productName();
                String newProductName = productNameMapping.get(oldProductName);
                
                if (newProductName != null && !oldProductName.equals(newProductName)) {
                    RateListEntry updatedEntry = new RateListEntry(
                            entry.id(),
                            entry.date(),
                            entry.type(),
                            newProductName,
                            entry.size(),
                            entry.rate(),
                            entry.srNo(),
                            entry.createdAt()
                    );
                    rateListEntryRepository.save(updatedEntry);
                    updatedCount++;
                    logger.info("Migrated product name: '{}' -> '{}' for entry: {}", 
                            oldProductName, newProductName, entry.id());
                }
            }

            Map<String, Object> response = Map.of(
                    "success", true,
                    "message", "Product names migrated successfully",
                    "updatedCount", updatedCount
            );
            
            logger.info("Product name migration completed. Updated {} entries by user: {}", 
                    updatedCount, session.displayName());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error migrating product names", e);
            // SECURITY: Don't expose internal error details to client
            Map<String, Object> response = Map.of(
                    "success", false,
                    "message", "Error migrating product names. Please try again."
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @PutMapping("/product/{productName}/srno")
    public ResponseEntity<Map<String, Object>> updateProductSrNo(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String productName,
            @RequestBody Map<String, Object> request
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // SECURITY: Validate and sanitize path variable
        if (productName == null || productName.isBlank() || productName.length() > 200) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Invalid product name"));
        }
        
        // Sanitize product name (make final for lambda usage)
        final String sanitizedProductName = productName.trim();
        if (sanitizedProductName.contains("<") || sanitizedProductName.contains(">") || sanitizedProductName.contains("\0")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Invalid characters in product name"));
        }

        try {
            Object srNoObj = request.get("srNo");
            if (srNoObj == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "srNo is required"));
            }

            Integer srNo;
            if (srNoObj instanceof Number) {
                int srNoValue = ((Number) srNoObj).intValue();
                if (srNoValue <= 0) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("success", false, "message", "srNo must be greater than 0"));
                }
                srNo = srNoValue;
            } else if (srNoObj instanceof String) {
                try {
                    int srNoValue = Integer.parseInt((String) srNoObj);
                    if (srNoValue <= 0) {
                        return ResponseEntity.badRequest()
                                .body(Map.of("success", false, "message", "srNo must be greater than 0"));
                    }
                    srNo = srNoValue;
                } catch (NumberFormatException e) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("success", false, "message", "Invalid srNo format"));
                }
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "Invalid srNo format"));
            }

            // Find all entries with this product name
            List<RateListEntry> productEntries = rateListEntryRepository.findAll().stream()
                    .filter(e -> e.productName().equals(sanitizedProductName))
                    .toList();

            if (productEntries.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "message", "Product not found"));
            }

            // Check if srNo is already assigned to a different product
            List<RateListEntry> entriesWithSameSrNo = rateListEntryRepository.findAll().stream()
                    .filter(e -> e.srNo() != null && e.srNo().equals(srNo))
                    .filter(e -> !e.productName().equals(sanitizedProductName))
                    .toList();

            if (!entriesWithSameSrNo.isEmpty()) {
                // Get the product name that already has this srNo
                String existingProduct = entriesWithSameSrNo.get(0).productName();
                logger.warn("Duplicate srNo attempted: srNo={}, existingProduct={}, newProduct={} by user: {}", 
                        srNo, existingProduct, sanitizedProductName, session.displayName());
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("success", false, 
                                "message", String.format("Serial number %d is already assigned to product '%s'. Please use a different serial number.", 
                                        srNo, existingProduct)));
            }

            // Update all entries with the new srNo
            int updatedCount = 0;
            for (RateListEntry entry : productEntries) {
                RateListEntry updated = new RateListEntry(
                        entry.id(),
                        entry.date(),
                        entry.type(),
                        entry.productName(),
                        entry.size(),
                        entry.rate(),
                        srNo,
                        entry.createdAt()
                );
                rateListEntryRepository.save(updated);
                updatedCount++;
            }

            logger.info("Updated srNo to {} for product '{}' ({} entries) by user: {}", 
                    srNo, sanitizedProductName, updatedCount, session.displayName());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", String.format("Updated srNo to %d for product '%s' (%d entries)", srNo, productName, updatedCount),
                    "updatedCount", updatedCount
            ));
        } catch (Exception e) {
            logger.error("Error updating product srNo", e);
            // SECURITY: Don't expose internal error details to client
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Error updating serial number. Please try again."));
        }
    }

    @PostMapping("/migrate-srno")
    public ResponseEntity<Map<String, Object>> migrateSrNo(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = authSessionService.validate(extractToken(authHeader));
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Only admin can migrate
        if (!session.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            List<RateListEntry> allEntries = rateListEntryRepository.findAll();
            
            // Get unique product names
            Set<String> productNames = allEntries.stream()
                    .map(RateListEntry::productName)
                    .collect(java.util.stream.Collectors.toSet());

            // Assign srNo starting from 1 for products without srNo
            int currentSrNo = 1;
            int updatedCount = 0;
            
            // First, find the maximum existing srNo
            int maxSrNo = allEntries.stream()
                    .filter(e -> e.srNo() != null)
                    .mapToInt(RateListEntry::srNo)
                    .max()
                    .orElse(0);
            
            // Start assigning from maxSrNo + 1 for products without srNo
            currentSrNo = maxSrNo + 1;
            
            for (String productName : productNames) {
                // Check if this product already has an srNo
                List<RateListEntry> productEntries = allEntries.stream()
                        .filter(e -> e.productName().equals(productName))
                        .toList();
                
                boolean hasSrNo = productEntries.stream()
                        .anyMatch(e -> e.srNo() != null);
                
                if (!hasSrNo) {
                    // Assign srNo to all entries of this product
                    for (RateListEntry entry : productEntries) {
                        RateListEntry updated = new RateListEntry(
                                entry.id(),
                                entry.date(),
                                entry.type(),
                                entry.productName(),
                                entry.size(),
                                entry.rate(),
                                currentSrNo,
                                entry.createdAt()
                        );
                        rateListEntryRepository.save(updated);
                        updatedCount++;
                    }
                    currentSrNo++;
                }
            }

            Map<String, Object> response = Map.of(
                    "success", true,
                    "message", "srNo migration completed successfully",
                    "updatedCount", updatedCount
            );
            
            logger.info("srNo migration completed. Updated {} entries by user: {}", 
                    updatedCount, session.displayName());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error migrating srNo", e);
            // SECURITY: Don't expose internal error details to client
            Map<String, Object> response = Map.of(
                    "success", false,
                    "message", "Error migrating serial numbers. Please try again."
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}

