package com.example.backend.shared.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class ClientIpTest {
    private static MockHttpServletRequest request(String remote) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        return request;
    }

    @Test
    void ignoresSpoofedForwardedForWithoutRealIp() {
        MockHttpServletRequest request = request("172.18.0.5");
        request.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.9");

        assertEquals("172.18.0.5", ClientIp.of(request));
    }

    @Test
    void usesRealIpSetByNginxAndIgnoresForwardedFor() {
        MockHttpServletRequest request = request("172.18.0.5");
        request.addHeader("X-Real-IP", " 203.0.113.9 ");
        request.addHeader("X-Forwarded-For", "6.6.6.6");

        assertEquals("203.0.113.9", ClientIp.of(request));
    }

    @Test
    void acceptsAndNormalizesIpv6Literals() {
        MockHttpServletRequest request = request("172.18.0.5");
        request.addHeader("X-Real-IP", "2001:DB8:0:0:0:0:0:1");

        assertEquals("2001:db8:0:0:0:0:0:1", ClientIp.of(request));
        assertEquals("0:0:0:0:0:0:0:1", ClientIp.literal("::1"));
    }

    @Test
    void fallsBackToRemoteAddressForInvalidRealIp() {
        for (String invalid : new String[] {"", "   ", "not-an-ip", "localhost", "example.com", "1.2.3", "256.1.1.1",
                "01.2.3.4", "1.2.3.4, 5.6.7.8", "2001:db8::zz", ":::", "fe80::1%eth0",
                "1".repeat(46), "1111:2222:3333:4444:5555:6666:7777:8888:9999:0000"}) {
            MockHttpServletRequest request = request("10.0.0.7");
            request.addHeader("X-Real-IP", invalid);
            assertEquals("10.0.0.7", ClientIp.of(request), () -> "accepted " + invalid);
        }
    }
}
