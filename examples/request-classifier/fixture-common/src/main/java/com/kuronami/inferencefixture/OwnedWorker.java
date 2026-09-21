package com.kuronami.inferencefixture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** 明示した破壊試験だけが使う。自分の直系子、同じJava、同じキャッシュの厳密な起動形だけ。 */
final class OwnedWorker {
    private OwnedWorker() {}
    static List<ProcessHandle> find() throws Exception {
        List<ProcessHandle> result = new ArrayList<>();
        for (ProcessHandle child : ProcessHandle.current().children().toList()) {
            if (matches(child)) result.add(child);
        }
        return result;
    }

    static boolean matches(ProcessHandle child) throws Exception {
        if (!child.isAlive() || child.parent().map(p -> p.pid() != ProcessHandle.current().pid()).orElse(true)) return false;
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        Path expectedJava = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toRealPath();
        String command = child.info().command().orElse("");
        // conhost等のWindows内部パスはJavaのPathで表せない場合がある。対象外の子は照会前に除く。
        if (!command.isEmpty()) {
            try {
                if (!Files.isSameFile(Path.of(command), expectedJava)) return false;
            } catch (java.nio.file.InvalidPathException invalid) {
                return false;
            }
        }
        String[] args = child.info().arguments().orElse(null);
        if (windows && args == null) {
            String[] info = windowsInfo(child.pid());
            if (info == null) return false;
            command = info[0];
            List<String> words = splitWindows(info[1]);
            if (words.isEmpty() || !Files.isSameFile(Path.of(words.getFirst()), expectedJava)) return false;
            args = words.subList(1, words.size()).toArray(String[]::new);
        }
        if (command.isEmpty() || args == null || args.length != 4) return false;
        if (!Files.isSameFile(Path.of(command), expectedJava)) return false;
        if (!args[0].equals("-Xmx2G") || !args[1].equals("-jar") || !args[3].equals("--worker")) return false;
        Path jar = Path.of(args[2]).toRealPath();
        Path cache = Path.of(".localinferenceapi", "runtime").toRealPath();
        return Files.isSameFile(jar.getParent(), cache)
                && jar.getFileName().toString().matches("runtime-[0-9a-f]{64}\\.jar")
                && child.isAlive() && child.parent().map(p -> p.pid() == ProcessHandle.current().pid()).orElse(false);
    }

    // WindowsのProcessHandleがargumentsを公開しない場合も、列挙済みの直系子PIDだけを照合する。
    private static String[] windowsInfo(long pid) throws Exception {
        String script = "$ErrorActionPreference='Stop'; $p=Get-CimInstance Win32_Process -Filter 'ProcessId=" + pid
                + "'; if($null -ne $p) { [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]$p.ExecutablePath));"
                + "[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]$p.CommandLine)) }";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        Process query = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!query.waitFor(15, TimeUnit.SECONDS)) {
            query.destroyForcibly(); // このメソッド自身が起動した照会だけを終了する。
            throw new IOException("Process query timed out");
        }
        if (query.exitValue() != 0) throw new IOException("Process query failed");
        String[] lines = new String(query.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip().split("\\R");
        if (lines.length != 2) return null;
        return new String[] {new String(Base64.getDecoder().decode(lines[0]), StandardCharsets.UTF_8),
                new String(Base64.getDecoder().decode(lines[1]), StandardCharsets.UTF_8)};
    }

    // 自分の起動形にはエスケープ引用符はない。想定外の形は許容せず拒否する。
    private static List<String> splitWindows(String commandLine) throws IOException {
        var matcher = Pattern.compile("\\s*(?:\"([^\"]*)\"|([^\\s\"]+))").matcher(commandLine);
        List<String> result = new ArrayList<>();
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) throw new IOException("Unexpected process command line");
            result.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
            end = matcher.end();
        }
        if (!commandLine.substring(end).isBlank()) throw new IOException("Unexpected process command line tail");
        return result;
    }
}
