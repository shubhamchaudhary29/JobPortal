package com.example.backend.integration.aggregation.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.backend.integration.aggregation.EmployerRegistryAuditDocument;
import com.example.backend.integration.aggregation.EmployerRegistryDocument;
import com.example.backend.shared.security.JwtUtil;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class AdminEmployerRegistryMongoIntegrationTest {
    private static final String BASE = "/api/v1/admin/ingestion/registry";
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    @AfterEach
    void clear() {
        mongo.remove(new Query(), EmployerRegistryDocument.class);
        mongo.remove(new Query(), EmployerRegistryAuditDocument.class);
    }

    @Test
    void everyRouteIsAdminOnly() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(get(BASE), post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"),
                patch(BASE + "/x").contentType(MediaType.APPLICATION_JSON).content("{}"), post(BASE + "/x/validation"),
                get(BASE + "/x/audit"))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer("USER"))).andExpect(status().isForbidden());
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer("RECRUITER"))).andExpect(status().isForbidden());
        }
        mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))).andExpect(status().isOk());
        mvc.perform(post(BASE + "/missing/validation").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void createListUpdateEnableDisableAreAuditedWithTheActor() throws Exception {
        String sarvam = create("{\"provider\":\"ashby\",\"boardId\":\"sarvam\",\"company\":\"Sarvam AI\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.provider").value("ashby"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.origin").value("ADMIN"))
                .andExpect(jsonPath("$.createdBy").value("admin@example.com"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(sarvam, "$.id");
        create("{\"provider\":\"lever\",\"boardId\":\"cred\",\"company\":\"CRED\",\"enabled\":false}").andExpect(status().isCreated());

        admin(get(BASE + "?provider=ashby")).andExpect(jsonPath("$.totalElements").value(1));
        admin(get(BASE + "?enabled=false")).andExpect(jsonPath("$.content[0].boardId").value("cred"));
        admin(get(BASE + "?q=SARV")).andExpect(jsonPath("$.content[0].company").value("Sarvam AI"));
        admin(get(BASE + "?q=.*")).andExpect(jsonPath("$.totalElements").value(0));
        admin(get(BASE)).andExpect(jsonPath("$.content[0].provider").value("ashby"))
                .andExpect(jsonPath("$.content[1].provider").value("lever"));

        admin(patch(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"disabledReason\":\"board moved\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.disabledReason").value("board moved"));
        admin(patch(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.disabledReason").doesNotExist());
        admin(patch(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"company\":\"Sarvam\"}"))
                .andExpect(jsonPath("$.company").value("Sarvam")).andExpect(jsonPath("$.updatedBy").value("admin@example.com"));
        admin(patch(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"company\":\"Sarvam\"}"))
                .andExpect(status().isOk());

        admin(get(BASE + "/" + id + "/audit"))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].action").value("UPDATE"))
                .andExpect(jsonPath("$.content[0].before.company").value("Sarvam AI"))
                .andExpect(jsonPath("$.content[0].after.company").value("Sarvam"))
                .andExpect(jsonPath("$.content[1].action").value("ENABLE"))
                .andExpect(jsonPath("$.content[2].action").value("DISABLE"))
                .andExpect(jsonPath("$.content[2].after.disabledReason").value("board moved"))
                .andExpect(jsonPath("$.content[3].action").value("CREATE"))
                .andExpect(jsonPath("$.content[3].actor").value("admin@example.com"));
    }

    @Test
    void duplicatesConflictAndInvalidInputIsRejected() throws Exception {
        String created = create("{\"provider\":\"greenhouse\",\"boardId\":\"groww\",\"company\":\"Groww\"}")
                .andReturn().getResponse().getContentAsString();
        String cred = JsonPath.read(create("{\"provider\":\"lever\",\"boardId\":\"cred\",\"company\":\"CRED\"}")
                .andReturn().getResponse().getContentAsString(), "$.id");

        create("{\"provider\":\"greenhouse\",\"boardId\":\"GROWW\",\"company\":\"Other\"}").andExpect(status().isConflict());
        create("{\"provider\":\"ashby\",\"boardId\":\"groww\",\"company\":\" groww \"}").andExpect(status().isConflict());
        create("{\"provider\":\"ashby\",\"boardId\":\"groww\",\"company\":\"Groww\",\"enabled\":false}").andExpect(status().isCreated());
        admin(patch(BASE + "/" + cred).contentType(MediaType.APPLICATION_JSON).content("{\"company\":\"groww\"}"))
                .andExpect(status().isConflict());
        for (String invalid : List.of("{\"provider\":\"adzuna\",\"boardId\":\"x\",\"company\":\"X\"}",
                "{\"provider\":\"linkedin\",\"boardId\":\"x\",\"company\":\"X\"}",
                "{\"provider\":\"lever\",\"boardId\":\"bad/board\",\"company\":\"X\"}",
                "{\"provider\":\"lever\",\"boardId\":\"x\",\"company\":\" \"}",
                "{\"provider\":\"lever\",\"boardId\":\"x\",\"company\":\"X\",\"unexpected\":1}")) {
            create(invalid).andExpect(status().isBadRequest());
        }
        admin(get(BASE + "?size=0")).andExpect(status().isBadRequest());
        admin(get(BASE + "?size=101")).andExpect(status().isBadRequest());
        admin(get(BASE + "?page=-1")).andExpect(status().isBadRequest());
        admin(get(BASE + "?provider=jooble")).andExpect(status().isBadRequest());
        assertNotNull(JsonPath.read(created, "$.id"));
        assertEquals(3, mongo.count(new Query(), EmployerRegistryDocument.class));
    }

    private org.springframework.test.web.servlet.ResultActions create(String body) throws Exception {
        return admin(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private org.springframework.test.web.servlet.ResultActions admin(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")));
    }

    private String bearer(String role) {
        return "Bearer " + jwt.generateToken(role.equals("ADMIN") ? "admin@example.com" : "user@example.com", role);
    }
}
