package com.finalysis.config;

import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** {@link ExposureTest} with every named profile active: none may expose more than the default. */
@ActiveProfiles({"dev", "local", "aws"})
class ExposureWithProfilesTest extends ExposureTest {

    ExposureWithProfilesTest(MockMvc mockMvc, Environment environment) {
        super(mockMvc, environment);
    }
}
