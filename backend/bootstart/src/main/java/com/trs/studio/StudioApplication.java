package com.trs.studio;

import com.trs.app.UserAccount;
import com.trs.app.UserRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Graph Agent Simulation Studio entry point. @author agent */
@SpringBootApplication(scanBasePackages = "com.trs")
@EntityScan("com.trs.app")
@EnableJpaRepositories("com.trs.app")
public class StudioApplication {
    /** Starts the REST and static web server. @author agent */
    public static void main(String[] args) {
        SpringApplication.run(StudioApplication.class, args);
    }

    /** Seeds the initial account once; override its password for production. @author agent */
    @Bean
    public ApplicationRunner seed(UserRepository users, org.springframework.core.env.Environment env) {
        return args -> {
            if (users.findByUsername("admin").isEmpty())
                users.save(new UserAccount(
                        "admin",
                        new BCryptPasswordEncoder().encode(env.getProperty("studio.admin-password", "123456"))));
        };
    }
}
