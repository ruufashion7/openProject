package org.example.auth;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends MongoRepository<User, String> {
    /** Use {@code findFirst} so migrated data with duplicate usernames does not throw NonUniqueResultException. */
    Optional<User> findFirstByUsernameOrderByIdAsc(String username);

    Optional<User> findFirstByUsernameAndActiveTrueOrderByIdAsc(String username);

    List<User> findAllByActiveTrueOrderByDisplayNameAsc();

    List<User> findAllByOrderByDisplayNameAsc();

    /** At most one row expected; {@code findFirst} tolerates duplicate admin rows after bad imports. */
    Optional<User> findFirstByIsAdminTrueAndActiveTrueOrderByIdAsc();

    List<User> findByIsAdminTrue();
}

