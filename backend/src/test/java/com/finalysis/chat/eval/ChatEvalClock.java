package com.finalysis.chat.eval;

import com.finalysis.support.MutableClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A settable clock standing in for the app's {@code Clock} in the live classes (step 8): each dataset's "today". */
@TestConfiguration(proxyBeanMethods = false)
class ChatEvalClock {

    @Bean
    @Primary
    MutableClock evalClock() {
        return new MutableClock();
    }
}
