package com.kuronami.localinferenceapi.internal.som;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

/**
 * artifact download の transport seam。製品は {@link HttpFetcher}
 * (HTTPS + host allowlist + redirect を手動検証)を使う。試験は loopback
 * server や local file map を差し込める。呼出側は Response.status を自分で
 * 判定する。
 */
public interface Fetcher {

    /**
     * uri を read のために開く。resumeFrom > 0 はその offset 以降の bytes を
     * 要求する(Range request)。Range を無視する server は status 200 と完全な
     * body を返す — 呼出側はその場合 append せず最初から取り直す。
     *
     * @throws FetchRejected URI または redirect 先が policy(scheme・host
     *         allowlist・redirect depth)に違反 — fail-closed で retry しない。
     */
    Response open(URI uri, long resumeFrom) throws IOException, InterruptedException;

    record Response(int status, InputStream body) implements AutoCloseable {
        @Override public void close() throws IOException { body.close(); }
    }

    /** policy 違反 — 決定的な拒否であり retry しない。 */
    final class FetchRejected extends IOException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        public FetchRejected(String message) { super(message); }
    }
}
