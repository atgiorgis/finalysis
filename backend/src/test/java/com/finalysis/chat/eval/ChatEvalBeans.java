package com.finalysis.chat.eval;

import com.finalysis.account.AccountRepository;
import com.finalysis.categorize.MerchantIndexRefresh;
import com.finalysis.chat.ChatModelClient;
import com.finalysis.chat.ChatProperties;
import com.finalysis.chat.ChatService;
import com.finalysis.support.MutableClock;
import com.finalysis.chat.tool.ToolParam;
import com.finalysis.chat.tool.ToolRegistry;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The chat evaluation's beans (docs/plans/phase-6.md step 8): the call recorder around the chat model, the seeder and
 * the runner. The live classes also import {@link ChatEvalClock}; a test context with its own settable clock
 * doesn't.
 */
@TestConfiguration(proxyBeanMethods = false)
class ChatEvalBeans {

    /** Static: a post-processor must exist before the beans it wraps. */
    @Bean
    static ChatCallRecorder chatCallRecorder(ObjectProvider<ToolRegistry> registry) {
        return new ChatCallRecorder(name -> registry.getObject().find(name)
                .map(tool -> tool.params().stream().map(ToolParam::name).collect(Collectors.toUnmodifiableSet())));
    }

    @Bean
    ChatEvalSeeder chatEvalSeeder(MockMvc mvc, AccountRepository accounts, JdbcTemplate jdbc, JsonMapper json,
                                  MerchantIndexRefresh refresh) {
        return new ChatEvalSeeder(mvc, accounts, jdbc, json, refresh);
    }

    @Bean
    ChatEvalRunner chatEvalRunner(ChatService chat, ChatModelClient client, ChatEvalSeeder seeder,
                                  ChatCallRecorder recorder, MutableClock clock, JdbcTemplate jdbc,
                                  ConfigurableEnvironment environment, ChatProperties limits) {
        return new ChatEvalRunner(chat, client, seeder, recorder, clock, jdbc, environment, limits);
    }
}
