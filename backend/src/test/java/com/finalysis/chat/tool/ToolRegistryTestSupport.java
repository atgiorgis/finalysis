package com.finalysis.chat.tool;

/** The application's registry without Spring, for tests outside this package. */
public final class ToolRegistryTestSupport {

    private ToolRegistryTestSupport() {
    }

    public static ToolRegistry registry() {
        return new ToolRegistry(ToolRegistryTest.tools(), ToolRegistryTest.JSON);
    }
}
