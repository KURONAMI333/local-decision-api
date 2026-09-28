package com.kuronami.localinferenceapi.internal.som;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 2 phase の release 検証。common/som-pin/verify_manifest.py 及び
 * som-windows-java-bootstrap の ManifestVerifier から移植し、acceptance
 * review の結果をさらに hardening した。
 *
 * Phase 1 — verifyManifest(bytes, TrustedPin): VerifiedManifest を得る唯一の
 *   経路。manifest bytes を pin digest へ結び付けた後、schema・field 型・
 *   safe path・重複 path・sha256 構文・size・role・license/notice 参照・
 *   upstream URL を全て検査する — network も artifact I/O も走る前に。
 *   sidecar-as-authenticator も local_reference も dev mode も無い。
 *
 * Phase 2 — Stager.stage(VerifiedManifest, …) が artifact bytes を取得・
 *   検証して公開する(Stager を参照)。
 *
 * verifyPackageTree(root, manifestPath, trustedSha256) は既に staged された
 *   declared-path tree(root 下に manifest の path 通りに置かれた全 file が
 *   symlink 無し・hash/size 一致で存在する)を検査する — Python verifier の
 *   file-existence 検査との parity のため残す diagnostics。製品の cache
 *   layout({@code artifacts/<sha256>}) の検査は {@link Stager#inspect} が
 *   担当する。diagnostics 専用なので package-private — ここで得た
 *   VerifiedManifest を Stager へ渡してはいけない(token 発行の正規経路は
 *   verifyManifest だけ)。
 *
 * Fail-closed: malformed な入力は failure を蓄積し、VerifiedManifest が null
 *   の場合は「検証されていない」を意味する。
 *
 * manifest の追加契約(sandbox より厳しい部分):
 *   - manifest_version == 1 を必須とする
 *   - role は "runtime" | "weights" | "license" のいずれか
 *   - runtime / weights artifact は upstream_url(allowlist 内の https)と
 *     空でない license_files を必須とする — launch 前に license/notice の
 *     存在を要求する fail-closed ゲート
 *   - license artifact は licenses/ 配下の path を必須とし、bytes は JAR
 *     同梱 resource からのみ stage される(upstream_url は provenance 記録)
 *   - size は 0 &lt; size ≤ pin.maxArtifactBytes
 */
public final class ManifestVerifier {

    private static final Pattern SHA256_SYNTAX = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final long MAX_MANIFEST_BYTES = 4L << 20; // sanity bound
    private static final long MAX_SIDECAR_BYTES = 4L << 10;  // manifest.sha256
    /** Gson 2.10.1 の再帰 parser へ無制限の nesting を渡さないための前置 bound。 */
    private static final int MAX_JSON_DEPTH = 96;
    private static final Set<String> ROLES = Set.of("runtime", "weights", "license");
    private static final String LICENSE_DIR = "licenses/";

    /**
     * 検証済み artifact の型付き view。platform は artifact を対象実行環境へ
     * 束ねる任意キー(例 "macos-arm64")— 省略時は全 platform 共通。archive は
     * runtime artifact の包装形式("tar.gz"/"zip")、entry は展開後の起動
     * binary の相対名。members は展開後ファイル → sha256 の pin map(生成物;
     * archive bytes 自体も sha256 で pin されるが、展開物の改変検出は spawn
     * 時の member 再照合が担う — 生 artifact の再 hash では展開物の差替えを
     * 検出できないため)。
     */
    public record Artifact(String path, String sha256, long size, String role,
                           String upstreamUrl, List<String> licenseFiles,
                           String id, String platform, String archive,
                           String entry, java.util.Map<String, String> members) {}

    public record Result(List<String> failures, List<String> warnings,
                         VerifiedManifest manifest) {
        public boolean passed() { return failures.isEmpty(); }
    }

    private ManifestVerifier() {}

    // ---------- phase 1: manifest metadata + trust anchor ----------

    /** bytes 形 — download 経路の canonical entry point。 */
    public static Result verifyManifest(byte[] manifestBytes, TrustedPin pin) {
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (pin == null) {
            failures.add("no trusted manifest digest supplied (required in release mode)");
            return new Result(failures, warnings, null);
        }
        if (manifestBytes == null || manifestBytes.length == 0) {
            failures.add("manifest empty");
            return new Result(failures, warnings, null);
        }
        if (manifestBytes.length > MAX_MANIFEST_BYTES) {
            failures.add("manifest too large: " + manifestBytes.length + " bytes");
            return new Result(failures, warnings, null);
        }
        String actualDigest = sha256Hex(manifestBytes);
        if (!actualDigest.equalsIgnoreCase(pin.manifestSha256().trim())) {
            failures.add("manifest TAMPERED: sha256 " + actualDigest
                    + " != trusted " + pin.manifestSha256().trim().toLowerCase(Locale.ROOT));
            return new Result(failures, warnings, null); // anchor 失敗 → parse しない
        }
        if (!jsonDepthBounded(manifestBytes)) {
            // verifyPackageTree の diagnostics 経路では attacker-controlled
            // bytes がここへ届く — 再帰 parser への depth 爆弾を前置で遮る
            failures.add("manifest malformed: JSON nesting exceeds depth bound");
            return new Result(failures, warnings, null);
        }

        JsonObject manifestJson;
        try {
            JsonElement parsed = JsonParser.parseString(new String(manifestBytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("artifacts")
                    || !parsed.getAsJsonObject().get("artifacts").isJsonArray()) {
                failures.add("manifest malformed: missing 'artifacts' list");
                return new Result(failures, warnings, null);
            }
            manifestJson = parsed.getAsJsonObject();
        } catch (RuntimeException malformed) {
            failures.add("manifest malformed JSON: " + malformed.getMessage());
            return new Result(failures, warnings, null);
        }

        JsonElement version = manifestJson.get("manifest_version");
        int versionValue;
        try {
            versionValue = version != null && version.isJsonPrimitive()
                    && version.getAsJsonPrimitive().isNumber()
                    ? version.getAsBigDecimal().intValueExact() : -1;
        } catch (ArithmeticException notAnInteger) {
            versionValue = -1;
        }
        if (versionValue != 1) {
            failures.add("unsupported manifest_version (expected 1)");
            return new Result(failures, warnings, null);
        }

        List<Artifact> artifacts = new ArrayList<>();
        Set<String> declaredPaths = new LinkedHashSet<>();
        Set<String> licensePaths = new LinkedHashSet<>();

        for (JsonElement el : manifestJson.getAsJsonArray("artifacts")) {
            Artifact art = parseArtifact(el, failures, pin);
            if (art == null) continue;
            if (!declaredPaths.add(art.path())) {
                failures.add("duplicate artifact path: " + art.path());
                continue;
            }
            licensePaths.addAll(art.licenseFiles());
            artifacts.add(art);
        }

        if (manifestJson.getAsJsonArray("artifacts").isEmpty()) {
            // 空集合の宣言は vacuous COMMITTED を生み得る — 拒否する
            // (全件 malformed の場合は entry 毎の failure が既に積まれる)
            failures.add("manifest declares no artifacts");
        }

        // license/notice 参照は宣言済み artifact へ解決できなければならない
        for (String lp : licensePaths) {
            if (!declaredPaths.contains(lp)) {
                failures.add("LICENSE file not declared as artifact: " + lp);
            }
        }

        if (!failures.isEmpty()) {
            return new Result(failures, warnings, null); // token を発行しない
        }
        return new Result(failures, warnings, new VerifiedManifest(artifacts, actualDigest));
    }

    // ---------- phase 1b: already-staged package tree ----------

    /**
     * 物理 package tree を manifest と照合する diagnostics。先に phase 1
     * (anchor + metadata)を走らせ、その後で宣言された全 artifact が root
     * 下に存在し、全 path 成分に symlink が無く、root 内に収まり、
     * size+sha256 が一致することを検査する。manifest.sha256 sidecar は存在
     * すれば検査する — manifest だけの編集を検出するが、認証にはならない。
     *
     * package-private: 呼出し側が caller 任意の trustedSha256 で token
     * (VerifiedManifest) を mint できるため、製品経路からは Stager.inspect
     * の cache-layout 検査を使い、ここは test/診断のみに留める。
     * Result.manifest() を Stager.stage へ渡してはいけない。
     */
    static Result verifyPackageTree(Path root, Path manifestPath, String trustedSha256) {
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!Files.isDirectory(root)) {
            failures.add("root not found: " + root);
            return new Result(failures, warnings, null);
        }
        if (!Files.isRegularFile(manifestPath)) {
            failures.add("manifest not found: " + manifestPath);
            return new Result(failures, warnings, null);
        }
        byte[] manifestBytes;
        try {
            // size 検査と readAllBytes の隙間に肥大化する same-uid swap でも
            // bound を破れないよう、cap+1 までしか読まない bounded read を通す
            manifestBytes = readBounded(manifestPath, MAX_MANIFEST_BYTES);
        } catch (IOException e) {
            failures.add("manifest unreadable: " + e.getMessage());
            return new Result(failures, warnings, null);
        }
        if (manifestBytes.length > MAX_MANIFEST_BYTES) {
            failures.add("manifest too large: " + manifestBytes.length + " bytes");
            return new Result(failures, warnings, null);
        }

        // opportunistic sidecar(mismatch があっても phase 1 前に報告する)。
        // bounded — 異常に大きい sidecar は cap+1 までしか読まず検査を skip する
        Path sidecar = manifestPath.resolveSibling("manifest.sha256");
        if (Files.isRegularFile(sidecar)) {
            try {
                byte[] sidecarBytes = readBounded(sidecar, MAX_SIDECAR_BYTES);
                if (sidecarBytes.length <= MAX_SIDECAR_BYTES) {
                    String expected = new String(sidecarBytes, StandardCharsets.UTF_8)
                            .split("\\s+")[0].trim();
                    String actual = sha256Hex(manifestBytes);
                    if (!expected.equalsIgnoreCase(actual)) {
                        failures.add("sidecar mismatch: manifest.sha256 pins " + abbrev(expected)
                                + " but manifest is " + abbrev(actual));
                    }
                }
            } catch (IOException | RuntimeException e) {
                warnings.add("manifest.sha256 sidecar unreadable: " + e.getMessage());
            }
        }

        // install 済み tree は URL allowlist を要さない — pin は host を持たない。
        // anchor が空でも diagnostics のため metadata + file 検査は実行するが、
        // 結果は失敗のまま留まり token は発行されない。
        boolean anchored = trustedSha256 != null && !trustedSha256.isBlank();
        if (!anchored) {
            failures.add("no trusted manifest digest supplied (required in release mode)");
        }
        Result phase1 = verifyManifest(manifestBytes,
                new TrustedPin(anchored ? trustedSha256 : sha256Hex(manifestBytes),
                        Set.of(), true, Long.MAX_VALUE));
        failures.addAll(phase1.failures());
        warnings.addAll(phase1.warnings());
        VerifiedManifest vm = phase1.manifest();
        if (vm == null) {
            return new Result(failures, warnings, null);
        }

        for (Artifact art : vm.artifacts()) {
            String rel = art.path();
            Path target = root.resolve(rel);
            Path link = RelPaths.firstSymlinkComponent(root, rel);
            if (link != null) {
                failures.add("SYMLINK in release path: " + rel + " (component " + link + ")");
                continue;
            }
            try {
                if (!RelPaths.resolvesInsideLenient(root, target)) {
                    failures.add("ESCAPE: " + rel + " resolves outside root");
                    continue;
                }
            } catch (IOException e) {
                failures.add("ESCAPE check failed for " + rel + ": " + e.getMessage());
                continue;
            }
            if (!Files.isRegularFile(target)) {
                failures.add("MISSING " + rel);
                continue;
            }
            try {
                long size = Files.size(target);
                String digest = sha256Hex(target); // streaming — 644MB でも安全
                if (size != art.size()) {
                    failures.add("SIZE " + rel + ": " + size + " != " + art.size());
                }
                if (!digest.equalsIgnoreCase(art.sha256())) {
                    failures.add("HASH " + rel + ": " + digest + " != " + art.sha256());
                }
            } catch (IOException e) {
                failures.add("unreadable artifact " + rel + ": " + e.getMessage());
            }
        }

        // license/notice file は物理的に存在し、symlink であってはならない
        Set<String> licensePaths = new LinkedHashSet<>();
        for (Artifact art : vm.artifacts()) licensePaths.addAll(art.licenseFiles());
        for (String lp : licensePaths) {
            Path link = RelPaths.firstSymlinkComponent(root, lp);
            if (link != null) {
                failures.add("LICENSE SYMLINK in release path: " + lp + " (component " + link + ")");
                continue;
            }
            if (!Files.isRegularFile(root.resolve(lp))) {
                failures.add("LICENSE file missing from package: " + lp);
            }
        }

        return new Result(failures, warnings, failures.isEmpty() ? vm : null);
    }

    // ---------- internals ----------

    /** 全 field の型検査を使用の前に行う — fail closed。 */
    private static Artifact parseArtifact(JsonElement el, List<String> failures, TrustedPin pin) {
        String repr = abbrev(String.valueOf(el));
        Runnable bad = () -> failures.add("malformed artifact entry: " + repr);
        if (!el.isJsonObject()) { bad.run(); return null; }
        JsonObject obj = el.getAsJsonObject();
        for (String field : new String[]{"path", "sha256", "size", "role"}) {
            if (!obj.has(field) || obj.get(field).isJsonNull()) { bad.run(); return null; }
        }
        JsonElement path = obj.get("path");
        JsonElement sha = obj.get("sha256");
        JsonElement size = obj.get("size");
        JsonElement role = obj.get("role");
        if (!path.isJsonPrimitive() || !path.getAsJsonPrimitive().isString()
                || !sha.isJsonPrimitive() || !sha.getAsJsonPrimitive().isString()
                || !size.isJsonPrimitive() || !size.getAsJsonPrimitive().isNumber()
                || !role.isJsonPrimitive() || !role.getAsJsonPrimitive().isString()) {
            bad.run(); return null;
        }
        String rel = path.getAsString();
        String shaStr = sha.getAsString();
        String roleStr = role.getAsString();
        long sizeValue;
        try {
            sizeValue = size.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException nonInteger) {
            bad.run(); return null;
        }

        if (!RelPaths.isSafe(rel)) {
            failures.add("UNSAFE path: " + rel);
            return null;
        }
        if (obj.has("local_reference")) {
            failures.add("local_reference not allowed in release manifest: " + rel);
            return null;
        }
        if (!SHA256_SYNTAX.matcher(shaStr).matches()) {
            failures.add("malformed sha256 (not 64 hex): " + rel);
            return null;
        }
        if (sizeValue <= 0) {
            failures.add("malformed size (not positive): " + rel);
            return null;
        }
        if (sizeValue > pin.maxArtifactBytes()) {
            failures.add("size exceeds pin cap " + pin.maxArtifactBytes() + ": " + rel);
            return null;
        }
        if (!ROLES.contains(roleStr)) {
            failures.add("malformed role (unknown): " + roleStr + " in " + rel);
            return null;
        }
        if ("license".equals(roleStr) && !rel.startsWith(LICENSE_DIR)) {
            failures.add("license artifact must live under " + LICENSE_DIR + ": " + rel);
            return null;
        }
        String idStr = null;
        JsonElement id = obj.get("id");
        if (id != null && !id.isJsonNull()) {
            if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) {
                failures.add("malformed id (not a string): " + rel);
                return null;
            }
            idStr = id.getAsString();
        }
        String platformStr = null;
        JsonElement platform = obj.get("platform");
        if (platform != null && !platform.isJsonNull()) {
            if (!platform.isJsonPrimitive() || !platform.getAsJsonPrimitive().isString()) {
                failures.add("malformed platform (not a string): " + rel);
                return null;
            }
            platformStr = platform.getAsString();
        }
        String archiveStr = null;
        JsonElement archive = obj.get("archive");
        if (archive != null && !archive.isJsonNull()) {
            if (!archive.isJsonPrimitive() || !archive.getAsJsonPrimitive().isString()
                    || !Set.of("tar.gz", "zip").contains(archive.getAsString())) {
                failures.add("malformed archive (not tar.gz/zip): " + rel);
                return null;
            }
            archiveStr = archive.getAsString();
        }
        String entryStr = null;
        JsonElement entry = obj.get("entry");
        if (entry != null && !entry.isJsonNull()) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()
                    || !RelPaths.isSafe(entry.getAsString())) {
                failures.add("malformed/unsafe entry path: " + rel);
                return null;
            }
            entryStr = entry.getAsString();
        }
        java.util.Map<String, String> members = java.util.Map.of();
        JsonElement membersEl = obj.get("members");
        if (membersEl != null && !membersEl.isJsonNull()) {
            if (!membersEl.isJsonObject()) {
                failures.add("malformed members (not an object): " + rel);
                return null;
            }
            java.util.Map<String, String> parsed = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, JsonElement> e
                    : membersEl.getAsJsonObject().entrySet()) {
                if (!RelPaths.isSafe(e.getKey()) || !e.getValue().isJsonPrimitive()
                        || !e.getValue().getAsJsonPrimitive().isString()
                        || !SHA256_SYNTAX.matcher(e.getValue().getAsString()).matches()) {
                    failures.add("malformed member pin " + abbrev(e.getKey()) + " in " + rel);
                    return null;
                }
                parsed.put(e.getKey(), e.getValue().getAsString());
            }
            if (parsed.isEmpty()) {
                failures.add("members pin map is empty: " + rel);
                return null;
            }
            members = java.util.Collections.unmodifiableMap(parsed);
        }

        JsonElement url = obj.get("upstream_url");
        String urlString = null;
        if (url != null && !url.isJsonNull()) {
            if (!url.isJsonPrimitive() || !url.getAsJsonPrimitive().isString()) {
                failures.add("malformed upstream_url: " + rel);
                return null;
            }
            urlString = url.getAsString();
            String err = checkUrl(urlString, rel, pin);
            if (err != null) { failures.add(err); return null; }
        } else if (!"license".equals(roleStr)) {
            // license bytes は JAR 同梱 resource が正本。runtime/weights は
            // fetch する upstream が無ければ取得不能 — fail closed。
            failures.add("upstream_url required for " + roleStr + " artifact: " + rel);
            return null;
        }

        List<String> licenseFiles = new ArrayList<>();
        JsonElement lfs = obj.get("license_files");
        if (lfs != null && !lfs.isJsonNull()) {
            if (!lfs.isJsonArray()) {
                failures.add("malformed license_files (not a list): " + rel);
                return null;
            }
            for (JsonElement lf : lfs.getAsJsonArray()) {
                if (!lf.isJsonPrimitive() || !lf.getAsJsonPrimitive().isString()
                        || !RelPaths.isSafe(lf.getAsString())) {
                    failures.add("malformed/unsafe license path " + abbrev(String.valueOf(lf))
                            + " in " + rel);
                    return null;
                }
                licenseFiles.add(lf.getAsString());
            }
        }
        if (!"license".equals(roleStr) && licenseFiles.isEmpty()) {
            // launch 前の notice 存在を fail-closed で要求する。
            failures.add("license_files required for " + roleStr + " artifact: " + rel);
            return null;
        }
        return new Artifact(rel, shaStr, sizeValue, roleStr,
                urlString, List.copyOf(licenseFiles), idStr, platformStr,
                archiveStr, entryStr, members);
    }

    private static String checkUrl(String url, String rel, TrustedPin pin) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return "malformed upstream_url for " + rel + ": " + e.getMessage();
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("https")
                && !(scheme.equals("http") && pin.allowPlainHttp()))) {
            return "upstream_url scheme rejected for " + rel + ": " + url;
        }
        if (!pin.hostAllowed(uri.getHost())) {
            return "upstream_url host not in pin allowlist for " + rel + ": " + uri.getHost();
        }
        return null;
    }

    /**
     * 文字列と escape を除いた bracket 深度の前置 bound。Gson 2.10.1 の
     * parser は再帰で depth 制限を持たないため、検証済みでない JSON を
     * parse する経路(commit marker 検査・unanchored diagnostics)の前に
     * 必ず通す — StackOverflowError で落ちる代わりに検査 failure にする。
     * `"` / `\` / bracket は ASCII のみなので UTF-8 解碼前の byte 走査で
     * 正しい(continuation byte がそれらと一致することはない)。
     */
    static boolean jsonDepthBounded(byte[] bytes) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (byte b : bytes) {
            if (inString) {
                if (escaped) escaped = false;
                else if (b == '\\') escaped = true;
                else if (b == '"') inString = false;
                continue;
            }
            if (b == '"') inString = true;
            else if (b == '{' || b == '[') {
                if (++depth > MAX_JSON_DEPTH) return false;
            } else if ((b == '}' || b == ']') && depth > 0) {
                depth--;
            }
        }
        return true;
    }

    /**
     * cap+1 byte までしか読まない bounded read — Files.size の事前検査と
     * readAllBytes の隙間に file が肥大化する same-uid swap(OOM 誘発)で
     * bound を破られないよう、読取り側で bound を強制する。戻り値の
     * length > cap なら file は bound 超過と判定する。cap は int 範囲内の
     * 小さい値(MANIFEST/SIDECAR/MARKER の各上限)前提。
     */
    static byte[] readBounded(Path path, long cap) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return in.readNBytes((int) Math.min(cap + 1, Integer.MAX_VALUE - 8));
        }
    }

    static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** streaming hash — 644MB の weight でも bounded buffer で読む。 */
    static String sha256Hex(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[128 * 1024];
            try (InputStream in = Files.newInputStream(path)) {
                for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String abbrev(String s) {
        s = String.valueOf(s);
        return s.length() <= 80 ? s : s.substring(0, 77) + "...";
    }
}
