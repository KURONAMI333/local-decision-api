package com.kuronami.localinferenceapi.internal.som;

import java.util.Locale;

/**
 * 実行中の platform を manifest の {@code platform} キーへ写す。
 * 対応外の platform は null — native runtime artifact が存在しないことを
 * 意味し、gate は NO_RUNTIME_ARTIFACT へ畳む(linux, windows-arm64,
 * macos-x64 未同梱等)。判定は os.name/os.arch のみ — JVM だけで決まるため
 * staging より前に呼んでも副作用は無い。
 */
public final class SomPlatform {

    private SomPlatform() {}

    /** 現在の platform キー。未対応は null。 */
    public static String detect() {
        return detect(System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""));
    }

    static String detect(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        String archKey = switch (arch) {
            case "aarch64", "arm64" -> "arm64";
            case "x86_64", "amd64", "x64" -> "amd64";
            default -> null;
        };
        if (archKey == null) return null;
        if (os.contains("mac") || os.contains("darwin")) {
            return "arm64".equals(archKey) ? "macos-arm64" : "macos-x64";
        }
        if (os.contains("win")) {
            return "amd64".equals(archKey) ? "windows-amd64" : "windows-arm64";
        }
        return null;
    }
}
