package com.finalysis.chat.outbound;

import com.finalysis.ai.outbound.RedactionContextQuery;
import com.finalysis.chat.tool.AccountAliasQuery;
import com.finalysis.chat.tool.CategoryNamesQuery;
import com.finalysis.chat.tool.MerchantSearchQuery;
import org.springframework.stereotype.Component;

/**
 * Starts a turn's {@link OutboundTurn}: aliases, the user's identifiers and the category names, read once; the stored
 * merchants only when the turn first resolves a merchant text.
 */
@Component
public class OutboundTurnFactory {

    private final AccountAliasQuery aliases;
    private final RedactionContextQuery redaction;
    private final AccountIdentityQuery identities;
    private final CategoryNamesQuery categories;
    private final MerchantOutbound merchants;
    private final MerchantSearchQuery storedMerchants;
    private final SearchTextOutbound search;

    public OutboundTurnFactory(AccountAliasQuery aliases, RedactionContextQuery redaction,
                               AccountIdentityQuery identities, CategoryNamesQuery categories,
                               MerchantOutbound merchants, MerchantSearchQuery storedMerchants,
                               SearchTextOutbound search) {
        this.aliases = aliases;
        this.redaction = redaction;
        this.identities = identities;
        this.categories = categories;
        this.merchants = merchants;
        this.storedMerchants = storedMerchants;
        this.search = search;
    }

    public OutboundTurn start() {
        return new OutboundTurn(aliases.load(), redaction.load(), identities.load(), categories.load(), merchants,
                storedMerchants::merchants, search);
    }
}
