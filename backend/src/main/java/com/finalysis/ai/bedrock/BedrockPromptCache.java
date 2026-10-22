package com.finalysis.ai.bedrock;

import com.finalysis.ai.PromptCache;
import java.util.Locale;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.bedrock.converse.api.BedrockCacheOptions;
import org.springframework.ai.bedrock.converse.api.BedrockCacheStrategy;
import org.springframework.ai.bedrock.converse.api.BedrockCacheTtl;
import org.springframework.ai.chat.prompt.ChatOptions;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;

/**
 * Bedrock Converse cache points through Spring AI 2.0.1's {@link BedrockCacheOptions} (step 8c): strategy
 * {@code SYSTEM_AND_TOOLS}, 5-minute TTL. Spring AI puts one {@code cachePoint} after the last tool definition and one
 * after a system block: the last one, or with {@code multiBlockSystemCaching} the one before it. Chat's system blocks
 * are the system prompt and the context block, then the conversation's summary when it has one, so the point lands
 * after the context block either way and the summary stays outside the cached prefix. {@code CONVERSATION_HISTORY},
 * which would cache the conversation itself, is never used.
 */
public class BedrockPromptCache implements PromptCache {

    @Override
    public ChatOptions withCachePoints(ChatOptions options, int systemMessages, int cachedSystemMessages) {
        if (!(options instanceof BedrockChatOptions bedrock)) {
            throw new IllegalArgumentException("Bedrock cache points need the Converse model's options");
        }
        boolean multiBlock;
        if (cachedSystemMessages == systemMessages && systemMessages >= 1) {
            multiBlock = false;
        } else if (cachedSystemMessages == systemMessages - 1 && cachedSystemMessages >= 1) {
            multiBlock = true;
        } else {
            throw new IllegalArgumentException("Bedrock can end a cached prefix only after the last or the "
                    + "second-to-last system message");
        }
        return bedrock.mutate().cacheOptions(BedrockCacheOptions.builder()
                .strategy(BedrockCacheStrategy.SYSTEM_AND_TOOLS)
                .multiBlockSystemCaching(multiBlock)
                .ttl(BedrockCacheTtl.FIVE_MINUTES)
                .build()).build();
    }

    /** Bedrock answers a model that can't cache, or a misplaced cache point, with a 400 naming the cache. */
    @Override
    public boolean refusedCaching(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause() == e ? null : e.getCause()) {
            if (e instanceof ValidationException v && v.getMessage() != null
                    && v.getMessage().toLowerCase(Locale.ROOT).contains("cach")) {
                return true;
            }
        }
        return false;
    }
}
