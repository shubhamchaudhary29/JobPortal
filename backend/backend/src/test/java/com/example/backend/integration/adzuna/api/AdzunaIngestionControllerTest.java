package com.example.backend.integration.adzuna.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.backend.shared.error.ForbiddenException;
import com.example.backend.integration.adzuna.AdzunaService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class AdzunaIngestionControllerTest {
    @Test
    void recruiterCanTriggerManualSync() {
        AdzunaService service = mock(AdzunaService.class);
        AdzunaService.SyncResult result = new AdzunaService.SyncResult(1, 0, 0, 0, 0, 0, AdzunaService.Outcome.FULL_SUCCESS);
        when(service.sync()).thenReturn(result);
        var authentication = new UsernamePasswordAuthenticationToken("recruiter@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_RECRUITER")));

        assertEquals(result, new AdzunaIngestionController(service).sync(authentication).getBody());
    }

    @Test
    void nonRecruiterCannotTriggerManualSync() {
        var authentication = new UsernamePasswordAuthenticationToken("user@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertThrows(ForbiddenException.class, () -> new AdzunaIngestionController(mock(AdzunaService.class)).sync(authentication));
    }
}
