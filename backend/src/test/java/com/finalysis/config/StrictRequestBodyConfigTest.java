package com.finalysis.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.finalysis.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Request bodies reject unknown fields (see AccountControllerTest); the shared mapper must not,
 * because Spring AI and HTTP clients read provider responses with it.
 */
@IntegrationTest
class StrictRequestBodyConfigTest {

    record Reply(String name) {
    }

    private final JsonMapper jsonMapper;

    StrictRequestBodyConfigTest(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Test
    void sharedMapperIgnoresUnknownFields() {
        Reply reply = jsonMapper.readValue("{\"name\":\"x\",\"extra\":1}", Reply.class);

        assertThat(reply.name()).isEqualTo("x");
    }
}
