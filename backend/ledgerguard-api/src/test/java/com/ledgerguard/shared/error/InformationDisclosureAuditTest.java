package com.ledgerguard.shared.error;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@ActiveProfiles("test")
class InformationDisclosureAuditTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    @DisplayName("500 Internal Server Error does not disclose exception class names, stack traces, or sensitive messages")
    void serverErrorDoesNotDiscloseInternalDetails() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/test/server-error"))
                .andReturn();

        String content = result.getResponse().getContentAsString();

        assertThat(content).doesNotContain("RuntimeException");
        assertThat(content).doesNotContain("Simulated sensitive database password failure");
        assertThat(content).doesNotContain("at com.ledgerguard");
        assertThat(content).doesNotContain("stackTrace");
        assertThat(content).doesNotContain("NullPointerException");
        assertThat(content).doesNotContain("org.springframework");
    }

    @Test
    @DisplayName("Malformed JSON 400 Bad Request does not disclose parser exception class names or stack traces")
    void malformedJsonDoesNotDiscloseParserDetails() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ unquoted_bad_json: 123 }"))
                .andReturn();

        String content = result.getResponse().getContentAsString();

        assertThat(content).doesNotContain("JsonParseException");
        assertThat(content).doesNotContain("JacksonException");
        assertThat(content).doesNotContain("com.fasterxml.jackson");
        assertThat(content).doesNotContain("stackTrace");
    }

    @Test
    @DisplayName("Validation 400 Bad Request does not disclose internal reflection or rejected values")
    void validationErrorDoesNotDiscloseInternalDetails() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "",
                                  "amount": -5
                                }
                                """))
                .andReturn();

        String content = result.getResponse().getContentAsString();

        assertThat(content).doesNotContain("MethodArgumentNotValidException");
        assertThat(content).doesNotContain("BindingResult");
        assertThat(content).doesNotContain("stackTrace");
    }
}
