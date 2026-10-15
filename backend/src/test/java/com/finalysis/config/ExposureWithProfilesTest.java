package com.finalysis.config;

import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link ExposureTest} with the named profiles active: none may expose more than the default. {@code aws} and
 * {@code local} can't be active together (startup fails, docs/plans/phase-4.md §7), so {@code local} has its own
 * run, {@link ExposureWithLocalProfileTest}. With {@code aws} the Bedrock clients are built but never called.
 */
@ActiveProfiles({"dev", "aws"})
class ExposureWithProfilesTest extends ExposureTest {

    ExposureWithProfilesTest(MockMvc mockMvc, Environment environment) {
        super(mockMvc, environment);
    }
}
