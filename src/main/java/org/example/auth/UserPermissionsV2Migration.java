package org.example.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * One-time backfill: add the three customer-master flags when missing.
 * They default to {@code false} so access is opt-in via Access Control (Customer Category / Notes / Location Edit).
 */
@Component
@Order(0)
public class UserPermissionsV2Migration implements ApplicationRunner {

    private final MongoTemplate mongoTemplate;

    public UserPermissionsV2Migration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
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

        // Backfill: users with Rate List page access get rate list upload unless explicitly absent
        Query q2 = new Query();
        q2.addCriteria(Criteria.where("permissions").exists(true));
        q2.addCriteria(Criteria.where("permissions.rateListPage").is(true));
        q2.addCriteria(Criteria.where("permissions.rateListUpload").exists(false));
        Update u2 = new Update().set("permissions.rateListUpload", true);
        mongoTemplate.updateMulti(q2, u2, User.class);
    }
}
