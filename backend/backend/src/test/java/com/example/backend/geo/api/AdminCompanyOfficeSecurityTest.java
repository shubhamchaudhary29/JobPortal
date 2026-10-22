package com.example.backend.geo.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.backend.geo.application.CompanyOfficeService;
import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.pagination.PageResponse;
import com.example.backend.shared.security.JwtUtil;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class AdminCompanyOfficeSecurityTest {
    private static final String BASE = "/api/v1/admin/geo/offices";
    private static final CompanyOfficeService.OfficeView OFFICE = new CompanyOfficeService.OfficeView("o1", "Acme", "acme",
            "Bellandur", "1277333", "Bengaluru", "Karnataka", 12.926, 77.676, true, Instant.EPOCH, "actor@example.com",
            Instant.EPOCH, "actor@example.com");
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @MockitoBean CompanyOfficeService offices;

    @Test
    void everyEndpointRequiresAdmin() throws Exception {
        when(offices.list(any(), any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.from(new PageImpl<>(List.of(OFFICE), PageRequest.of(0, 20), 1)));
        when(offices.candidates(50)).thenReturn(List.of(new CompanyOfficeService.Candidate("Acme", "acme", "1277333",
                "Bengaluru", "Karnataka", 12)));
        when(offices.create(any(), any(), any(), any(), any(), any())).thenReturn(OFFICE);
        when(offices.update(eq("o1"), any(), any(), any(), any(), any())).thenReturn(OFFICE);
        when(offices.audit(eq("o1"), anyInt(), anyInt()))
                .thenReturn(PageResponse.from(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0)));
        when(offices.osmReport()).thenReturn(new CompanyOfficeService.OsmReport(false, 10,
                new CompanyOfficeService.OsmBucket(1, 2), new CompanyOfficeService.OsmBucket(1, 12),
                new CompanyOfficeService.OsmBucket(0, 0), 12, List.of(new CompanyOfficeService.OsmMatch("Acme", "acme",
                "1277333", "Bengaluru", "Acme Campus", "osm-n1", "https://www.openstreetmap.org/node/1", 12))));
        when(offices.pickOsm(eq("osm-n1"), eq("acme"), eq("1277333"), any(), any())).thenReturn(OFFICE);
        when(offices.rejectOsm(eq("osm-n1"), eq("acme"), eq("1277333"), any())).thenReturn(new CompanyOfficeService.Decision(
                "acme", "1277333", "osm-n1", "REJECT", "admin@example.com", Instant.EPOCH));
        String pair = "{\"companyKey\":\"acme\",\"placeId\":\"1277333\"}";
        List<MockHttpServletRequestBuilder> requests = List.of(get(BASE), get(BASE + "/candidates"), get(BASE + "/o1/audit"),
                post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company\":\"Acme\",\"label\":\"Bellandur\",\"lat\":12.926,\"lng\":77.676}"),
                patch(BASE + "/o1").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"),
                get(BASE + "/osm-report"),
                post(BASE + "/osm/osm-n1/pick").contentType(MediaType.APPLICATION_JSON).content(pair),
                post(BASE + "/osm/osm-n1/reject").contentType(MediaType.APPLICATION_JSON).content(pair));
        for (MockHttpServletRequestBuilder request : requests) {
            mvc.perform(request).andExpect(status().isUnauthorized());
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer("USER"))).andExpect(status().isForbidden());
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer("RECRUITER"))).andExpect(status().isForbidden());
        }
        mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].label").value("Bellandur"));
        mvc.perform(get(BASE + "/candidates").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobs").value(12));
        mvc.perform(get(BASE + "/o1/audit").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))).andExpect(status().isOk());
        mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company\":\"Acme\",\"label\":\"Bellandur\",\"lat\":12.926,\"lng\":77.676}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value("o1"));
        verify(offices).create("Acme", "Bellandur", 12.926, 77.676, null, "admin@example.com");
        mvc.perform(patch(BASE + "/o1").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isOk());
        verify(offices).update("o1", null, null, null, false, "admin@example.com");
        mvc.perform(get(BASE + "/osm-report").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.topMatches[0].url").value("https://www.openstreetmap.org/node/1"))
                .andExpect(jsonPath("$.autoApply").value(false));
        mvc.perform(post(BASE + "/osm/osm-n1/pick").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(pair)).andExpect(status().isCreated());
        verify(offices).pickOsm("osm-n1", "acme", "1277333", null, "admin@example.com");
        mvc.perform(post(BASE + "/osm/osm-n1/reject").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(pair)).andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REJECT"));
        verify(offices).rejectOsm("osm-n1", "acme", "1277333", "admin@example.com");
        mvc.perform(post(BASE + "/osm/osm-n1/reject").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"companyKey\":\"acme\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void validationMessagesReachTheAdmin() throws Exception {
        when(offices.create(any(), any(), any(), any(), any(), any()))
                .thenThrow(new BadRequestException("Pin is far from Bengaluru (130 km); offices must be within 60 km of their city"));
        mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company\":\"Acme\",\"label\":\"HQ\",\"lat\":12.30,\"lng\":76.64,\"placeId\":\"1277333\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Pin is far from Bengaluru")));
        mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"company\":\"Acme\",\"label\":\"HQ\"}")).andExpect(status().isBadRequest());
    }

    private String bearer(String role) {
        return "Bearer " + jwt.generateToken(role.equals("ADMIN") ? "admin@example.com" : "actor@example.com", role);
    }
}
