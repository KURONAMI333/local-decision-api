package com.kuronami.localinferenceapi.internal.som;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JevK5 の option-letter readout — jevk5 v0.3.3 (allebee/jevk5) の
 * prompt.py / gguf.py を Java へ移植したもの。生成テキストを介さず、
 * llama-server の /tokenize → /completion(n_predict=1, n_probs=40,
 * temperature=0) が返す top_logprobs から選択肢 letter(A〜P)の分を取り、
 * 校正温度で softmax し直す — CUDA runtime と数学的に同一(letter logit の
 * softmax は full-vocab softmax の再正規化 slice と一致する)。
 *
 * 固定値(jevk5-4b-v0.3-*.gguf の model card pin 値):
 *   temperature 1.22          — 各 pass の letter softmax 校正
 *   knockout_temperature 0.93 — >16 option の結合分布の鋭化(MASSIVE fit)
 *   top_k 40                  — 16 letter を覆うに十分な prob 範囲
 *   MISSING_MARGIN 2.0        — top-k 外 letter は末尾より少なくともこれだけ低い
 *
 * prompt は Python json.dumps(ensure_ascii=False) と byte 一致させる
 * (区切りは ", " と ": "、非 ASCII は生、制御文字はバックスラッシュ-u
 * エスケープ系)。
 * >16 option は knockout(各 ≤16 group を読み、上位を決勝へ)を port —
 * 本 API の上限 24 choice では 2 group + 決勝 = 3 pass で閉じる。
 */
final class JevReadout {

    static final String LETTERS = "ABCDEFGHIJKLMNOP";
    static final double TEMPERATURE = 1.22;
    static final double KNOCKOUT_TEMPERATURE = 0.93;
    static final int TOP_K = 40;
    static final double MISSING_MARGIN = 2.0;
    /** prompt token の上限 — ctx 8192 に対し decode/集約の余裕を残す。 */
    static final int MAX_PROMPT_TOKENS = 7168;

    static final String SYSTEM =
            "Apply the supplied criterion to the supplied evidence. "
            + "Choose exactly one listed option. "
            + "Respond with only its uppercase letter, with no explanation or reasoning.";
    static final String CHAT_TEMPLATE =
            "<|im_start|>system\n{system}<|im_end|>\n"
            + "<|im_start|>user\n{user}<|im_end|>\n"
            + "<|im_start|>assistant\n<think>\n\n</think>\n\n";

    private JevReadout() {}

    /** Python json.dumps(ensure_ascii=False) の string literal と一致させる。 */
    static String pyJsonString(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c); // ensure_ascii=False — 非 ASCII は生
                }
            }
        }
        return out.append('"').toString();
    }

    /** prompt.py の messages()+CHAT_TEMPLATE 展開と byte 一致させる。 */
    static String promptText(String state, String criterion, List<String> optionTexts) {
        StringBuilder user = new StringBuilder(state.length() + 256);
        user.append("{\"evidence\": ").append(pyJsonString(state))
                .append(", \"criterion\": ").append(pyJsonString(criterion))
                .append(", \"options\": [");
        for (int i = 0; i < optionTexts.size(); i++) {
            if (i > 0) user.append(", ");
            user.append("{\"letter\": ").append(pyJsonString(String.valueOf(LETTERS.charAt(i))))
                    .append(", \"description\": ").append(pyJsonString(optionTexts.get(i)))
                    .append('}');
        }
        user.append("]}");
        return CHAT_TEMPLATE.replace("{system}", SYSTEM).replace("{user}", user.toString());
    }

    /** decision_options(question): (optionId, optionText) の順序付きペア。 */
    static List<Map.Entry<String, String>> decisionOptions(JsonObject q) {
        List<Map.Entry<String, String>> pairs = new ArrayList<>();
        String type = q.get("type").getAsString();
        JsonElement crit = q.get("criteria");
        if ("noul".equals(type)) {
            for (String k : List.of("true", "false")) {
                String d = "The proposition is " + k + ".";
                if (crit != null && crit.isJsonObject()
                        && crit.getAsJsonObject().has(k)
                        && crit.getAsJsonObject().get(k).isJsonPrimitive()) {
                    String v = crit.getAsJsonObject().get(k).getAsString();
                    if (v != null && !v.isEmpty()) d = v;
                }
                pairs.add(Map.entry(k, k + ": " + d));
            }
        } else if ("choice".equals(type)) {
            JsonObject c = crit.isJsonObject() ? crit.getAsJsonObject()
                    : listToKeys(crit.isJsonArray() ? crit.getAsJsonArray() : null);
            if (c == null) throw new IllegalArgumentException("choice criteria missing");
            for (String k : c.keySet()) {
                JsonElement v = c.get(k);
                String d = v != null && v.isJsonPrimitive()
                        && !v.getAsString().isEmpty() ? v.getAsString() : k;
                pairs.add(Map.entry(k, k + ": " + d));
            }
        } else if ("score".equals(type)) {
            JsonArray levels = crit != null && crit.isJsonArray() ? crit.getAsJsonArray() : null;
            if (levels == null) throw new IllegalArgumentException("score criteria missing");
            for (int i = 0; i < levels.size(); i++) {
                String id = String.valueOf(i);
                pairs.add(Map.entry(id, id + ": " + levels.get(i).getAsString()));
            }
        } else {
            throw new IllegalArgumentException("unknown question type: " + type);
        }
        return pairs;
    }

    private static JsonObject listToKeys(JsonArray list) {
        if (list == null) return null;
        JsonObject o = new JsonObject();
        for (JsonElement e : list) o.add(e.getAsString(), null);
        return o;
    }

    /** 1 pass の生結果 — 校正済み分布と letter 別の生 logprob。 */
    private record Pass(List<Double> probs, double[] letterLogprobs) {}

    /** 1 pass: texts(≤16)へ校正済み分布を返す。 */
    private static Pass readPass(Io io, String state,
                                 String instructions, List<String> texts,
                                 TokenBudget budget)
            throws IOException {
        int[] tokens = budget.tokenize(io, promptText(state, instructions, texts));
        LlamaClient.Completion c = io.completion(tokens, TOP_K);
        Map<String, Double> seen = c.topLogprobs();
        double floor = seen.values().stream().mapToDouble(d -> d).min().orElse(0.0)
                - MISSING_MARGIN;
        double[] lp = new double[texts.size()];
        for (int i = 0; i < texts.size(); i++) {
            lp[i] = seen.getOrDefault(String.valueOf(LETTERS.charAt(i)), floor);
        }
        double top = Double.NEGATIVE_INFINITY;
        for (double z : lp) top = Math.max(top, z);
        double[] w = new double[lp.length];
        double sum = 0;
        for (int i = 0; i < lp.length; i++) {
            w[i] = Math.exp((lp[i] - top) / TEMPERATURE);
            sum += w[i];
        }
        List<Double> out = new ArrayList<>(lp.length);
        for (double x : w) out.add(x / sum);
        return new Pass(out, lp);
    }

    /** llama-server との I/O seam — 製品は {@link LlamaClient}、試験は fake。 */
    interface Io {
        int[] tokenize(String content) throws IOException;
        LlamaClient.Completion completion(int[] tokens, int topK) throws IOException;
    }

    /** prompt.spread(read, texts, "knockout", 0.93) の移植。≤16 は直接 —
     *  knockout_temperature の鋭化は結合分布のみに掛かる(上流と同じ)。 */
    static List<Double> spread(Io io, String state, String instructions,
                               List<String> texts, TokenBudget budget) throws IOException {
        if (texts.size() <= LETTERS.length()) {
            return readPass(io, state, instructions, texts, budget).probs();
        }
        List<Double> probs = knockout(io, state, instructions, texts, budget);
        double total = probs.stream().mapToDouble(d -> d).sum();
        List<Double> normed = probs.stream().map(p -> p / total).toList();
        List<Double> sharp = new ArrayList<>(normed.size());
        double s = 0;
        for (double p : normed) {
            double v = Math.pow(p, 1.0 / KNOCKOUT_TEMPERATURE);
            sharp.add(v);
            s += v;
        }
        final double fs = s;
        return sharp.stream().map(v -> v / fs).toList();
    }

    private static List<Double> knockout(Io io, String state,
                                         String instructions, List<String> texts,
                                         TokenBudget budget) throws IOException {
        int n = texts.size();
        if (n <= LETTERS.length()) {
            return readPass(io, state, instructions, texts, budget).probs();
        }
        int groups = (n + LETTERS.length() - 1) / LETTERS.length();
        int base = n / groups, extra = n % groups;
        List<int[]> runs = new ArrayList<>();
        for (int g = 0, start = 0; g < groups; g++) {
            int stop = start + base + (g < extra ? 1 : 0);
            runs.add(new int[]{start, stop});
            start = stop;
        }
        // 各 group を読み group 内で正規化
        List<List<Double>> inner = new ArrayList<>();
        for (int[] run : runs) {
            List<String> sub = texts.subList(run[0], run[1]);
            List<Double> p = readPass(io, state, instructions, sub, budget).probs();
            double s = p.stream().mapToDouble(d -> d).sum();
            inner.add(p.stream().map(x -> x / s).toList());
        }
        int keep = Math.max(1, LETTERS.length() / groups);
        // 各 group の上位 keep + 残りは全 group 横断で確率順に詰める
        List<int[]> chosen = new ArrayList<>();
        List<int[]> rest = new ArrayList<>();
        for (int g = 0; g < runs.size(); g++) {
            List<Double> p = inner.get(g);
            List<Integer> order = new ArrayList<>();
            for (int j = 0; j < p.size(); j++) order.add(j);
            order.sort(Comparator.<Integer>comparingDouble(j -> p.get(j)).reversed()
                    .thenComparingInt(j -> j)); // tie は早い index
            for (int j = 0; j < Math.min(keep, order.size()); j++) {
                chosen.add(new int[]{g, order.get(j)});
            }
            for (int j = Math.min(keep, order.size()); j < order.size(); j++) {
                rest.add(new int[]{g, order.get(j)});
            }
        }
        rest.sort(Comparator.<int[]>comparingDouble(gj -> inner.get(gj[0]).get(gj[1]))
                .reversed());
        for (int i = 0; i < Math.max(0, LETTERS.length() - chosen.size()); i++) {
            chosen.add(rest.get(i));
        }
        // 決勝 pass: 選ばれた option を group 順・group 内 index 順で並べる
        List<List<Integer>> tops = new ArrayList<>();
        for (int g = 0; g < runs.size(); g++) tops.add(new ArrayList<>());
        for (int[] c : chosen) tops.get(c[0]).add(c[1]);
        for (List<Integer> t : tops) t.sort(Integer::compareTo);
        List<String> finalists = new ArrayList<>();
        for (int g = 0; g < runs.size(); g++) {
            for (int j : tops.get(g)) finalists.add(texts.get(runs.get(g)[0] + j));
        }
        List<Double> fin = knockout(io, state, instructions, finalists, budget);
        // shares: group 内 index → final の share
        List<Map<Integer, Double>> shares = new ArrayList<>();
        int at = 0;
        for (int g = 0; g < runs.size(); g++) {
            Map<Integer, Double> m = new LinkedHashMap<>();
            for (int j : tops.get(g)) m.put(j, fin.get(at++));
            shares.add(m);
        }
        double inFinal = 0;
        for (int g = 0; g < runs.size(); g++) {
            double shareSum = shares.get(g).values().stream().mapToDouble(d -> d).sum();
            List<Double> innerG = inner.get(g);
            double inProb = shares.get(g).keySet().stream()
                    .mapToDouble(j -> innerG.get(j)).sum();
            inFinal += shareSum * inProb;
        }
        List<Double> weights = new ArrayList<>(n);
        for (int g = 0; g < runs.size(); g++) {
            int size = runs.get(g)[1] - runs.get(g)[0];
            double mass = shares.get(g).values().stream().mapToDouble(d -> d).sum();
            for (int j = 0; j < size; j++) {
                if (shares.get(g).containsKey(j)) weights.add(shares.get(g).get(j) * inFinal);
                else weights.add(mass * inner.get(g).get(j));
            }
        }
        return weights;
    }

    /**
     * probabilities(state, question): option id → 校正済み確率。
     * input の token budget 超過は state → instructions の順で prefix 切詰め
     * で吸収し、truncated flag を立てる。option 本文は決して切らない
     * (letter↔description の対応を壊すため) — option だけで budget を
     * 超える場合は IllegalArgumentException で request を拒否する。
     */
    record Probs(Map<String, Double> probabilities, int inputTokens,
                 boolean truncated, List<Double> letterLogprobs) {}

    /** state/instructions を budget へ収めるために tokenize を共有する helper。 */
    static final class TokenBudget {
        int lastPassTokens;
        int totalTokens;
        boolean truncated;

        int[] tokenize(Io io, String prompt) throws IOException {
            int[] ids = io.tokenize(prompt);
            lastPassTokens = Math.max(lastPassTokens, ids.length);
            totalTokens += ids.length;
            return ids;
        }
    }

    static Probs probabilities(Io io, String state, JsonObject question)
            throws IOException {
        JsonElement ins = question.get("instructions");
        JsonElement type = question.get("type");
        if (ins == null || !ins.isJsonPrimitive() || type == null
                || !type.isJsonPrimitive()) {
            throw new IllegalArgumentException("question missing type/instructions");
        }
        String instructions = ins.getAsString();
        List<Map.Entry<String, String>> options = decisionOptions(question);
        if (options.isEmpty()) {
            throw new IllegalArgumentException("question has no options");
        }
        List<String> texts = options.stream().map(Map.Entry::getValue).toList();
        TokenBudget budget = new TokenBudget();
        // budget 評価は実際に送信される pass prompt(≤16 option)で行う。
        // >16 では group/決勝 pass へ分割されるため、最長 16 option の
        // prompt が全 pass の上界になる(letters は length に寄与しない)。
        List<String> fitTexts = texts.size() <= LETTERS.length() ? texts
                : texts.stream()
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .limit(LETTERS.length()).toList();
        // budget に収まるまで state → instructions の順に prefix を二分で切る
        String fitState = fitState(io, state, instructions, fitTexts, budget);
        String fitInstr = instructions;
        if (fitState == null) { // state を空にしても足りない → instructions を切る
            fitState = "";
            fitInstr = fitInstructions(io, instructions, fitTexts, budget);
            if (fitInstr == null) {
                throw new IllegalArgumentException("question exceeds model context");
            }
        }
        List<Double> probs;
        List<Double> logits;
        if (texts.size() <= LETTERS.length()) {
            Pass pass = readPass(io, fitState, fitInstr, texts, budget);
            probs = pass.probs();
            // ≤16 の単一 pass では letter 別の生 logprob を製品 logits として
            // 透過する(knockout は pass 結合後の確率のみ意味を持つため zeros)。
            logits = new ArrayList<>(options.size());
            for (double l : pass.letterLogprobs()) logits.add(l);
        } else {
            probs = spread(io, fitState, fitInstr, texts, budget);
            logits = java.util.Collections.nCopies(options.size(), 0.0);
        }
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < options.size(); i++) {
            map.put(options.get(i).getKey(), probs.get(i));
        }
        return new Probs(map, budget.totalTokens, budget.truncated, logits);
    }

    /**
     * state を二分で短縮して budget に収める。収まれば(切詰めた)値を返し、
     * state を空にしても option 群だけで超過なら null。収束は tokenize
     * 呼出 ≤16 回(log2 32768)で決定的 — 切詰め境界は「char 数で最長の
     * prefix」。
     */
    private static String fitState(Io io, String state,
                                   String instructions, List<String> texts,
                                   TokenBudget budget) throws IOException {
        if (io.tokenize(promptText(state, instructions, texts)).length
                <= MAX_PROMPT_TOKENS) {
            return state;
        }
        budget.truncated = true;
        int lo = 0, hi = state.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            int n = io.tokenize(promptText(state.substring(0, mid),
                    instructions, texts)).length;
            if (n <= MAX_PROMPT_TOKENS) lo = mid; else hi = mid - 1;
        }
        return lo == 0 ? null : state.substring(0, lo);
    }

    /** state を空にしても超過する時の instructions 側の二分短縮。 */
    private static String fitInstructions(Io io, String instructions,
                                          List<String> texts, TokenBudget budget)
            throws IOException {
        if (io.tokenize(promptText("", instructions, texts)).length
                <= MAX_PROMPT_TOKENS) {
            return instructions;
        }
        int lo = 0, hi = instructions.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            int n = io.tokenize(promptText("", instructions.substring(0, mid),
                    texts)).length;
            if (n <= MAX_PROMPT_TOKENS) lo = mid; else hi = mid - 1;
        }
        return lo == 0 ? null : instructions.substring(0, lo);
    }

    /** server.py の answer() と同じ形の JEV answer object(+製品透過 field)。 */
    static JsonObject answer(JsonObject question, Probs probs) {
        String kind = question.get("type").getAsString();
        JsonObject out = new JsonObject();
        out.addProperty("type", kind);
        double conf = probs.probabilities().values().stream()
                .mapToDouble(d -> d).max().orElse(0.0);
        out.addProperty("confidence", conf);
        out.addProperty("input_tokens", probs.inputTokens());
        if (probs.truncated()) out.addProperty("truncated", true);
        if ("noul".equals(kind)) {
            out.addProperty("noul", probs.probabilities().get("true"));
        } else if ("choice".equals(kind)) {
            String best = null;
            double bv = Double.NEGATIVE_INFINITY;
            for (Map.Entry<String, Double> e : probs.probabilities().entrySet()) {
                if (e.getValue() > bv) { bv = e.getValue(); best = e.getKey(); }
            }
            out.addProperty("choice", best);
            JsonObject p = new JsonObject();
            probs.probabilities().forEach(p::addProperty);
            out.add("probabilities", p);
            JsonArray logits = new JsonArray();
            for (double l : probs.letterLogprobs()) logits.add(l);
            out.add("logits", logits);
        } else {
            double score = 0;
            int selected = 0;
            double sp = -1;
            JsonObject p = new JsonObject();
            for (Map.Entry<String, Double> e : probs.probabilities().entrySet()) {
                score += Integer.parseInt(e.getKey()) * e.getValue();
                p.addProperty(e.getKey(), e.getValue());
                if (e.getValue() > sp) { sp = e.getValue(); selected = Integer.parseInt(e.getKey()); }
            }
            out.addProperty("score", score);
            out.add("probabilities", p);
            out.addProperty("selected", selected);
        }
        return out;
    }
}
