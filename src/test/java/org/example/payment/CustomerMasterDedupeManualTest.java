package org.example.payment;

import org.example.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Run manually to dedupe customer_master and add unique index (not part of default {@code mvn test}):
 * ./mvnw test -Dtest=CustomerMasterDedupeManualTest -Ddedupe.customer.master=true
 */
@SpringBootTest(classes = Main.class)
@EnabledIfSystemProperty(named = "dedupe.customer.master", matches = "true")
class CustomerMasterDedupeManualTest {

    @Autowired
    private CustomerMasterDedupeService dedupeService;

    @Test
    void runDedupeAndEnsureUniqueIndex() {
        CustomerMasterDedupeService.DedupeResult r = dedupeService.dedupeAndEnsureUniqueIndex();
        System.out.println("Dedupe result: " + r);
    }
}
