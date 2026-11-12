package com.essor.qms.shared;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
class SharedConfig {

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new TenantAwareTransactionManager(dataSource);
    }
}
