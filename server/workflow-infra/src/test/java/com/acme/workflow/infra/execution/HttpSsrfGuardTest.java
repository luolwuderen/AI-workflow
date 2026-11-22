package com.acme.workflow.infra.execution;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpSsrfGuardTest {

    @Test
    void rejectsPrivateLoopbackAndReservedTargets() {
        HttpSsrfGuard guard = guard("", Map.of(
                "internal.example", List.of("10.0.0.8"),
                "loop.example", List.of("127.0.0.1"),
                "mixed.example", List.of("93.184.216.34", "192.168.1.10"),
                "ula.example", List.of("fd00::1"),
                "mapped.example", List.of("::ffff:10.0.0.1"),
                "cgnat.example", List.of("100.64.0.1"),
                "benchmark.example", List.of("198.18.0.1")));

        for (String host : List.of("internal.example", "loop.example", "mixed.example", "ula.example",
                "mapped.example", "cgnat.example", "benchmark.example")) {
            assertThatThrownBy(() -> guard.validate("http://" + host + "/api"))
                    .as(host)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SSRF");
        }
    }

    @Test
    void allowsPublicAddresses() {
        HttpSsrfGuard guard = guard("", Map.of("public.example", List.of("93.184.216.34")));

        assertThat(guard.validate("https://public.example/path")).hasToString("https://public.example/path");
    }

    @Test
    void allowlistBypassesAddressChecks() {
        HttpSsrfGuard guard = guard("localhost, *.corp.example", Map.of());

        assertThat(guard.validate("http://localhost:8199/hook")).hasToString("http://localhost:8199/hook");
        assertThat(guard.validate("http://api.corp.example/x")).hasToString("http://api.corp.example/x");
        assertThatThrownBy(() -> guard.validate("http://corp.example.evil/x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsupportedSchemesCredentialsAndMalformedUrls() {
        HttpSsrfGuard guard = guard("", Map.of());

        assertThatThrownBy(() -> guard.validate("ftp://public.example/file"))
                .hasMessageContaining("仅支持 http/https");
        assertThatThrownBy(() -> guard.validate("file:///etc/passwd"))
                .hasMessageContaining("仅支持 http/https");
        assertThatThrownBy(() -> guard.validate("http://user:pass@public.example/"))
                .hasMessageContaining("用户凭证");
        assertThatThrownBy(() -> guard.validate("not-a-url"))
                .hasMessageContaining("仅支持 http/https");
        assertThatThrownBy(() -> guard.validate("http:///missing-host"))
                .hasMessageContaining("缺少主机名");
    }

    private static HttpSsrfGuard guard(String allowed, Map<String, List<String>> dns) {
        return new HttpSsrfGuard(allowed, host -> {
            List<String> addresses = dns.get(host);
            if (addresses == null) throw new UnknownHostException(host);
            List<InetAddress> resolved = new ArrayList<>();
            for (String address : addresses) resolved.add(InetAddress.getByName(address));
            return resolved;
        });
    }
}
