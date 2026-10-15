package com.finalysis.config;

import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** {@link ExposureTest} with the {@code local} profile, which can't run together with {@code aws}. */
@ActiveProfiles({"dev", "local"})
class ExposureWithLocalProfileTest extends ExposureTest {

    ExposureWithLocalProfileTest(MockMvc mockMvc, Environment environment) {
        super(mockMvc, environment);
    }
}
