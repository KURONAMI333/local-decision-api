package com.kuronami.localinferenceapi.internal.som;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 製品の fetcher: HTTPS のみ、host allowlist(suffix match)、redirect は
 * java.net.http の自動追従を使わず手動で辿る(各 hop の target を同じ
 * policy で再検証する)。最大 4 hop。
 *
 * allowed host の各 entry はその host 完全一致または任意 subdomain に合致
 * する(例: "hf.co" は "cas-bridge.xethub.hf.co" や "cdn-lfs-us-1.hf.co" を
 * 含む。GitHub の release asset は githubusercontent.com 系、HF の LFS は
 * hf.co 系の CDN へ redirect する — pin には実測した hop 先を列挙する)。
 */
public final class HttpFetcher implements Fetcher {

    private static final int MAX_HOPS = 4;

    private final HttpClient client;
    private final boolean requireHttps;
    private final Set<String> allowedHosts;

    private HttpFetcher(boolean requireHttps, Set<String> allowedHosts) {
        this.requireHttps = requireHttps;
        this.allowedHosts = new TreeSet<>(allowedHosts);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER) // policy: 全 hop を検証する
                .build();
    }

    /** release 構成: HTTPS 必須。 */
    public static HttpFetcher production(Set<String> allowedHosts) {
        return new HttpFetcher(true, allowedHosts);
    }

    /** 試験専用: http:// loopback を許す。製品経路へ配線しない。 */
    static HttpFetcher insecureForTests(Set<String> allowedHosts) {
        return new HttpFetcher(false, allowedHosts);
    }

    boolean hostAllowed(URI uri) {
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String allowed : allowedHosts) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) return true;
        }
        return false;
    }

    private void checkPolicy(URI uri) throws FetchRejected {
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("https") && !(scheme.equals("http") && !requireHttps))) {
            throw new FetchRejected("scheme rejected: " + uri);
        }
        if (!hostAllowed(uri)) {
            throw new FetchRejected("host not in allowlist: " + uri);
        }
    }

    @Override
    public Response open(URI uri, long resumeFrom) throws IOException, InterruptedException {
        URI current = uri;
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            checkPolicy(current);
            HttpResponse<InputStream> res;
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder(current)
                        .timeout(Duration.ofMinutes(10)).GET();
                if (resumeFrom > 0) req.header("Range", "bytes=" + resumeFrom + "-");
                res = client.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IllegalArgumentException bad) {
                throw new FetchRejected("invalid request URI: " + current);
            }
            int status = res.statusCode();
            if (status >= 300 && status < 400) {
                res.body().close();
                String location = res.headers().firstValue("Location").orElse(null);
                if (location == null) throw new FetchRejected("redirect without Location: " + current);
                URI next;
                try {
                    // Location は server 制御の文字列 — malformed な値は
                    // RuntimeException を呼出側へ漏らさず policy 拒否に畳む
                    next = current.resolve(location.trim());
                } catch (IllegalArgumentException bad) {
                    throw new FetchRejected("malformed redirect Location: "
                            + location.trim());
                }
                current = next; // loop 先頭で再検証 — hop 毎に scheme + host を見る
                continue;
            }
            return new Response(status, res.body());
        }
        throw new FetchRejected("redirect depth exceeded (>" + MAX_HOPS + "): " + uri);
    }
}
