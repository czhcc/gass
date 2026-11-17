package com.trs.app;

import jakarta.persistence.*;
import lombok.Getter;

/** Persisted studio account. @author agent */
@Entity
@Table(name = "user_account")
@Getter
public class UserAccount {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(columnDefinition = "INTEGER")
  private Long id;

  @Column(nullable = false, unique = true)
  private String username;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  /** JPA constructor. @author agent */
  protected UserAccount() {}

  /** Creates an account using an encoded password. @author agent */
  public UserAccount(String username, String passwordHash) {
    this.username = username;
    this.passwordHash = passwordHash;
  }
}
