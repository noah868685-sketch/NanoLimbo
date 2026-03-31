package com.nanolimbo;

import java.io.*;
import java.net.*;
import java.nio.file.*;

public class NanoLimbo {
    public static void main(String[] args) {
        System.out.println("=== 鱼哥专属: 官方纯净 Hy2 模式激活 ===");
        try {
            runOfficialHy2();
        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void runOfficialHy2() throws Exception {
        String workDir = System.getProperty("java.io.tmpdir");
        Path hy2Path = Paths.get(workDir, "hy2_official");
        Path configPath = Paths.get(workDir, "config.yaml");

        // 1. 下载官方内核 (amd64)
        if (!Files.exists(hy2Path)) {
            String downloadUrl = "https://github.com/apernet/hysteria/releases/latest/download/hysteria-linux-amd64";
            System.out.println("正在从 GitHub 官方获取内核...");
            try (InputStream in = new URL(downloadUrl).openStream()) {
                Files.copy(in, hy2Path, StandardCopyOption.REPLACE_EXISTING);
            }
            hy2Path.toFile().setExecutable(true);
            System.out.println("内核下载完成！");
        }

        // 2. 生成配置文件 (25565端口)
        String uuid = System.getenv().getOrDefault("UUID", "yuge_science_fish_666");
        String config = "listen: :25565\nauth: " + uuid + "\ntls:\n  cert: self_signed\n";
        Files.writeString(configPath, config);
        System.out.println("配置已生成，端口: 25565");

        // 3. 启动
        ProcessBuilder pb = new ProcessBuilder(
            hy2Path.toString(), "server", "--config", configPath.toString()
        );
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        System.out.println("正在启动服务...");
        Process p = pb.start();
        p.waitFor();
    }
}
