package com.finalysis.ai.outbound;

import com.finalysis.categorize.MerchantCleaner;

/** A rule fixture (PackageDependencyTest): outbound code reaching back into a feature. */
public class UsesCategorize {

    String normalize(String text) {
        return MerchantCleaner.normalize(text);
    }
}
