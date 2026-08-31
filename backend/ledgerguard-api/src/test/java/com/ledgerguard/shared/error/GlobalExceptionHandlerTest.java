package com.ledgerguard.shared.error;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    @DisplayName("Valid request succeeds with 200 OK")
    void validRequestSucceeds() throws Exception {
        mockMvc.perform(post("/api/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Acme Corp",
                                  "amount": 100
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("VALID")))
                .andExpect(jsonPath("$.name", is("Acme Corp")));
    }

    @Test
    @DisplayName("Bean validation failure returns 400 Bad Request with ProblemDetail and VALIDATION_FAILED")
    void validationFailureReturnsProblemDetail() throws Exception {
        mockMvc.perform(post("/api/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "",
                                  "amount": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title", is("Validation failed")))
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.detail", is("One or more request fields are invalid.")))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.VALIDATION_FAILED)))
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.instance", is("/api/test/validate")))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "amount")))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')].message").value(containsInAnyOrder("must not be blank")))
                .andExpect(jsonPath("$.errors[?(@.field == 'amount')].message").value(containsInAnyOrder("must be greater than or equal to 1")));
    }

    @Test
    @DisplayName("Malformed JSON returns 400 Bad Request with ProblemDetail and MALFORMED_REQUEST")
    void malformedJsonReturnsProblemDetail() throws Exception {
        mockMvc.perform(post("/api/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ invalid json syntax ..."))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title", is("Malformed request")))
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.detail", is("The request body is malformed or could not be parsed.")))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.MALFORMED_REQUEST)))
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.instance", is("/api/test/validate")));
    }

    @Test
    @DisplayName("Unhandled server exception returns 500 Internal Server Error with safe detail and INTERNAL_ERROR")
    void unhandledExceptionReturnsSafeProblemDetail() throws Exception {
        mockMvc.perform(get("/api/test/server-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title", is("Internal Server Error")))
                .andExpect(jsonPath("$.status", is(500)))
                .andExpect(jsonPath("$.detail", is("An unexpected error occurred.")))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.INTERNAL_ERROR)))
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.instance", is("/api/test/server-error")));
    }

    @Test
    @DisplayName("Unmapped route returns 404 Not Found with ProblemDetail and RESOURCE_NOT_FOUND")
    void unmappedRouteReturnsProblemDetail() throws Exception {
        mockMvc.perform(get("/api/non-existent-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title", is("Resource not found")))
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.detail", is("The requested resource was not found.")))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.RESOURCE_NOT_FOUND)))
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.instance", is("/api/non-existent-endpoint")));
    }

    @Test
    @DisplayName("Unsupported HTTP method returns 405 Method Not Allowed with ProblemDetail")
    void unsupportedMethodReturns405() throws Exception {
        mockMvc.perform(put("/api/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Acme Corp",
                                  "amount": 100
                                }
                                """))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status", is(405)))
                .andExpect(jsonPath("$.title", is("Method Not Allowed")));
    }

    @Test
    @DisplayName("Unsupported media type returns 415 Unsupported Media Type with ProblemDetail")
    void unsupportedMediaTypeReturns415() throws Exception {
        mockMvc.perform(post("/api/test/validate")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("name=Acme"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status", is(415)))
                .andExpect(jsonPath("$.title", is("Unsupported Media Type")));
    }
}
