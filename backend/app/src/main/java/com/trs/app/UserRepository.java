package com.trs.app;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** User persistence operations. @author agent */
public interface UserRepository extends JpaRepository<UserAccount, Long> {
  /** Finds the unique login name. @author agent */
  Optional<UserAccount> findByUsername(String username);
}
