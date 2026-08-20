package com.semojum.backend.global.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IP → 위치 문자열 (T1-4 "IP · 위치") — 저장하지 않고 표시 시점에 조회한다(기획: 과거 작업도 최신 DB로 조회).
 * 외부 조회(ip-api.com) + Redis 캐시 24h. 실패·사설 IP·비활성은 null — 위치는 부가 정보라 화면을 막지 않는다.
 * ⚠️ ip-api.com 무료는 비상업 조건·분당 45회 — 내부 운영 콘솔 수준. 정식 운영 확장 시 유료 플랜 또는 제공자 교체(geoip.lookup-url).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeoIpResolver {

    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final Pattern FIELD = Pattern.compile("\"(status|country|regionName|city)\"\\s*:\\s*\"([^\"]*)\"");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${geoip.enabled:true}")
    private boolean enabled;

    @Value("${geoip.lookup-url:http://ip-api.com/json/%s?fields=status,country,regionName,city}")
    private String lookupUrl;

    /** 위치 문자열(예: "Seoul, South Korea") 또는 null */
    public String resolve(String ip) {
        if (!enabled || ip == null || ip.isBlank() || isPrivate(ip)) return null;
        String cacheKey = "geoip:" + ip;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) return cached.isEmpty() ? null : cached;
        } catch (Exception ignored) { /* 캐시 장애는 조회로 진행 */ }

        String location = lookup(ip);
        try {
            redisTemplate.opsForValue().set(cacheKey, location == null ? "" : location, CACHE_TTL);
        } catch (Exception ignored) { }
        return location;
    }

    private String lookup(String ip) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(String.format(lookupUrl, ip)))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return null;
            String status = null, country = null, region = null, city = null;
            Matcher m = FIELD.matcher(res.body());
            while (m.find()) {
                switch (m.group(1)) {
                    case "status" -> status = m.group(2);
                    case "country" -> country = m.group(2);
                    case "regionName" -> region = m.group(2);
                    case "city" -> city = m.group(2);
                }
            }
            if (status != null && !"success".equals(status)) return null;
            StringBuilder sb = new StringBuilder();
            if (city != null && !city.isBlank()) sb.append(city);
            if (region != null && !region.isBlank() && !region.equals(city)) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(region);
            }
            if (country != null && !country.isBlank()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(country);
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            log.debug("GeoIP 조회 실패(위치 생략): ip={}, error={}", ip, e.getMessage());
            return null;
        }
    }

    // 사설·루프백·링크로컬 — 외부 조회 무의미
    static boolean isPrivate(String ip) {
        return ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("127.")
                || ip.startsWith("169.254.") || ip.equals("0:0:0:0:0:0:0:1") || ip.equals("::1")
                || (ip.startsWith("172.") && isPrivate172(ip));
    }

    private static boolean isPrivate172(String ip) {
        try {
            int second = Integer.parseInt(ip.split("\\.")[1]);
            return second >= 16 && second <= 31;
        } catch (Exception e) {
            return false;
        }
    }
}
