package com.acme.workflow.infra.execution;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 校验 HTTP 目标地址，阻止指向内网、回环和保留地址的请求（SSRF）。
 * 可通过 workflow.http.allowed-hosts 显式放行主机，支持精确域名与 *.example.com 形式。
 */
@Component
public class HttpSsrfGuard {
    interface Resolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    private final List<String> allowedHosts;
    private final Resolver resolver;

    @Autowired
    public HttpSsrfGuard(@Value("${workflow.http.allowed-hosts:}") String allowedHosts) {
        this(allowedHosts, host -> List.of(InetAddress.getAllByName(host)));
    }

    HttpSsrfGuard(String allowedHosts, Resolver resolver) {
        this.allowedHosts = parse(allowedHosts);
        this.resolver = resolver;
    }

    public URI validate(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("URL 格式无效：" + rawUrl);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme))
            throw new IllegalArgumentException("仅支持 http/https 请求：" + rawUrl);
        String host = uri.getHost();
        if (host == null || host.isBlank())
            throw new IllegalArgumentException("URL 缺少主机名：" + rawUrl);
        if (uri.getUserInfo() != null)
            throw new IllegalArgumentException("URL 不允许包含用户凭证：" + rawUrl);
        String normalized = host.toLowerCase(Locale.ROOT);
        if (allowlisted(normalized)) return uri;
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(normalized);
        } catch (UnknownHostException error) {
            throw new IllegalArgumentException("无法解析主机：" + host);
        }
        if (addresses == null || addresses.isEmpty())
            throw new IllegalArgumentException("无法解析主机：" + host);
        for (InetAddress address : addresses) {
            if (blocked(address))
                throw new IllegalArgumentException("SSRF 已阻止：" + host + " 解析到内网或保留地址 " + address.getHostAddress());
        }
        return uri;
    }

    private boolean allowlisted(String host) {
        for (String allowed : allowedHosts) {
            if (allowed.startsWith("*.")) {
                if (host.endsWith(allowed.substring(1))) return true;
            } else if (host.equals(allowed)) {
                return true;
            }
        }
        return false;
    }

    private static boolean blocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return blockedIpv4(bytes);
        if (bytes.length == 16) {
            if ((bytes[0] & 0xfe) == 0xfc) return true;
            boolean mapped = true;
            for (int index = 0; index < 10; index++) {
                if (bytes[index] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff)
                return blockedIpv4(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
        }
        return false;
    }

    private static boolean blockedIpv4(byte[] bytes) {
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        if (first == 0 || first == 10 || first == 127) return true;
        if (first == 169 && second == 254) return true;
        if (first == 172 && second >= 16 && second <= 31) return true;
        if (first == 192 && second == 168) return true;
        if (first == 100 && second >= 64 && second <= 127) return true;
        if (first == 198 && (second == 18 || second == 19)) return true;
        return first >= 224;
    }

    private static List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> hosts = new ArrayList<>();
        for (String item : Arrays.asList(raw.split(","))) {
            String host = item.trim().toLowerCase(Locale.ROOT);
            if (!host.isEmpty()) hosts.add(host);
        }
        return List.copyOf(hosts);
    }
}
