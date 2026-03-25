package org.example.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Optional one-time or recovery run: same as POST /api/admin/customer-master/dedupe.
 * Enable with {@code app.customer-master.dedupe-on-startup=true}, then turn off.
 */
@Component
@Order(1)
@ConditionalOnProperty(name = "app.customer-master.dedupe-on-startup", havingValue = "true")
public class CustomerMasterDedupeStartupRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(CustomerMasterDedupeStartupRunner.class);

    private final CustomerMasterDedupeService dedupeService;

    public CustomerMasterDedupeStartupRunner(CustomerMasterDedupeService dedupeService) {
        this.dedupeService = dedupeService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            CustomerMasterDedupeService.DedupeResult r = dedupeService.dedupeAndEnsureUniqueIndex();
            logger.warn(
                    "app.customer-master.dedupe-on-startup=true: dedupe finished groupsMerged={} removed={} distinctKeys={}",
                    r.duplicateGroupsMerged(), r.documentsRemoved(), r.distinctCustomerKeys());
        } catch (Exception e) {
            logger.error("customer_master startup dedupe failed", e);
        }
    }
}
