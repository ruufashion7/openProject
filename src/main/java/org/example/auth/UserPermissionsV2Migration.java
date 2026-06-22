package org.example.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * One-time backfill: add permission flags when missing.
 * Failures do not stop application startup so a broken Atlas/network path does not kill the JVM
 * (e.g. frontend proxy "socket hang up" when Spring exits mid-startup).
 */
@Component
@Order(0)
public class UserPermissionsV2Migration implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(UserPermissionsV2Migration.class);

    private final MongoTemplate mongoTemplate;

    public UserPermissionsV2Migration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            runMigration();
        } catch (Exception e) {
            logger.error(
                    "User permissions backfill skipped (MongoDB unreachable). "
                            + "Fix connectivity, then restart or run equivalent updates manually. "
                            + "Checklist: Atlas → Network Access includes this machine's IP; "
                            + "test the same URI in Compass/mongosh; "
                            + "VPN/firewall allowing outbound TCP 27017; "
                            + "if SSLException internal_error (alert 80): Atlas Network Access must allow this host's "
                            + "public IP (or 0.0.0.0/0 for dev); try VPN off / Compass on same machine. "
                            + "Local DB: unset MONGO_URI → default mongodb://localhost:27017/openProject + docker compose mongo.",
                    e);
        }
    }

    private void runMigration() {
        Query q = new Query();
        q.addCriteria(Criteria.where("permissions").exists(true));
        q.addCriteria(new Criteria().orOperator(
                Criteria.where("permissions.detailsPage").is(true),
                Criteria.where("permissions.outstandingPage").is(true)
        ));
        q.addCriteria(Criteria.where("permissions.customerCategoryEdit").exists(false));
        Update u = new Update()
                .set("permissions.customerCategoryEdit", false)
                .set("permissions.customerNotesEdit", false)
                .set("permissions.customerLocationEdit", false);
        mongoTemplate.updateMulti(q, u, User.class);

        Query q2 = new Query();
        q2.addCriteria(Criteria.where("permissions").exists(true));
        q2.addCriteria(Criteria.where("permissions.rateListPage").is(true));
        q2.addCriteria(Criteria.where("permissions.rateListUpload").exists(false));
        Update u2 = new Update().set("permissions.rateListUpload", true);
        mongoTemplate.updateMulti(q2, u2, User.class);

        Query q3 = new Query();
        q3.addCriteria(Criteria.where("permissions").exists(true));
        q3.addCriteria(Criteria.where("permissions.whatsappDateChange").is(true));
        q3.addCriteria(Criteria.where("permissions.whatsappBroadcast").exists(false));
        Update u3 = new Update().set("permissions.whatsappBroadcast", true);
        mongoTemplate.updateMulti(q3, u3, User.class);

        Query q4 = new Query();
        q4.addCriteria(Criteria.where("permissions").exists(true));
        q4.addCriteria(Criteria.where("permissions.fileUpload").is(true));
        q4.addCriteria(new Criteria().orOperator(
                Criteria.where("permissions.uploadsListPage").exists(false),
                Criteria.where("permissions.uploadAuditPage").exists(false)
        ));
        Update u4 = new Update()
                .set("permissions.uploadsListPage", true)
                .set("permissions.uploadAuditPage", true);
        mongoTemplate.updateMulti(q4, u4, User.class);

        Query q5 = new Query();
        q5.addCriteria(Criteria.where("permissions").exists(true));
        q5.addCriteria(Criteria.where("permissions.customerExcludeEdit").exists(false));
        Update u5 = new Update().set("permissions.customerExcludeEdit", false);
        mongoTemplate.updateMulti(q5, u5, User.class);
    }
}
