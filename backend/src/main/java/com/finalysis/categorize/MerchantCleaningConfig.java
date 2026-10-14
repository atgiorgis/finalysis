package com.finalysis.categorize;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Loads the merchant cleaning rules once at startup; an invalid file fails startup (§2.2). */
@Configuration
class MerchantCleaningConfig {

    @Bean
    MerchantCleaningRules merchantCleaningRules() {
        return MerchantCleaningRules.loadDefault();
    }
}
