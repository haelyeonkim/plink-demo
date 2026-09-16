package com.plink.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdminBootstrap {

    /**
     * Lets a deployment create its first administrator from the environment. Re-running
     * with the same address resets that password, which is also the recovery path when
     * nobody can sign in any more.
     */
    @Bean
    ApplicationRunner bootstrapAdministrator(AdminAccountService accounts,
            @Value("${plink.admin.email:}") String email,
            @Value("${plink.admin.password:}") String password) {
        return args -> accounts.bootstrap(email, password);
    }
}
