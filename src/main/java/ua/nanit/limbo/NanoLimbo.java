/*
 * Copyright (C) 2020 Nan1t
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ua.nanit.limbo;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import ua.nanit.limbo.server.LimboServer;
import ua.nanit.limbo.server.Log;

public final class NanoLimbo {

    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED   = "\033[1;31m";
    private static final String ANSI_RESET = "\033[0m";
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process singboxProcess;

    // ========== 用户配置区 ==========
    private static final String HY2_PORT  = "25565";                    // hy2 端口，复用 Minecraft 游戏端口
    private static final String PASSWORD  = "239d90b954c420fe322d50a9"; // hy2 认证密码
    private static final String NODE_NAME = "xserver-jp";               // 节点备注名
    // ==================================

    public static void main(String[] args) {

        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
            System.err.println(ANSI_RED + "ERROR: Java version too low!" + ANSI_RESET);
            System.exit(1);
        }

        try {
            // 1. 下载 sing-box 官方二进制
            Path sbPath = downloadSingBox();

            // 2. 生成自签名 TLS 证书
            generateCert();

            // 3. 写入 sing-box 配置
            writeSingBoxConfig();

            // 4. 启动 sing-box
            startSingBox(sbPath);

            // 5. 注册关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopSingBox();
            }));

            // 6. 等待 sing-box 初始化
            Thread.sleep(5000);

            // 7. 获取服务器公网 IP
            String ip = getPublicIp();

            // 8. 生成节点链接并写入 sub.txt
            writeSubTxt(ip);

            // 9. 打印节点信息
            System.out.println(ANSI_GREEN + "\n========== 节点信息 ==========" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "hy2://" + PASSWORD + "@" + ip + ":" + HY2_PORT
                    + "?insecure=1&sni=bing.com#" + NODE_NAME + ANSI_RESET);
            System.out.println(ANSI_GREEN + "节点已写入 world/sub.txt" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "==============================\n" + ANSI_RESET);

            Thread.sleep(15000);
            clearConsole();

        } catch (Exception e) {
            System.err.println(ANSI_RED + "启动失败: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
        }

        // 启动 Minecraft Limbo（维持平台实例运行）
        try {
            new LimboServer().start();
        } catch (Exception e) {
            Log.error("Cannot start LimboServer: ", e);
        }
    }

    // -------- 下载 sing-box 官方二进制 --------
    private static Path downloadSingBox() throws Exception {
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sing-box");
        if (Files.exists(path)) {
            System.out.println(ANSI_GREEN + "sing-box 已存在，跳过下载" + ANSI_RESET);
            return path;
        }

        // 从 GitHub API 获取最新版本号
        String version = getLatestSingBoxVersion();
        System.out.println(ANSI_GREEN + "正在下载 sing-box " + version + " ..." + ANSI_RESET);

        String arch = System.getProperty("os.arch").toLowerCase();
        String archStr;
        if (arch.contains("amd64") || arch.contains("x86_64")) {
            archStr = "amd64";
        } else if (arch.contains("aarch64") || arch.contains("arm64")) {
            archStr = "arm64";
        } else {
            throw new RuntimeException("不支持的架构: " + arch);
        }

        // 官方 GitHub Releases 下载地址
        String versionNum = version.startsWith("v") ? version.substring(1) : version;
        String url = "https://github.com/SagerNet/sing-box/releases/download/"
                + version + "/sing-box-" + versionNum + "-linux-" + archStr + ".tar.gz";

        // 下载 tar.gz
        Path tarPath = Paths.get(System.getProperty("java.io.tmpdir"), "sing-box.tar.gz");
        downloadFile(url, tarPath);

        // 解压，提取 sing-box 二进制
        extractSingBox(tarPath, path, "sing-box-" + versionNum + "-linux-" + archStr + "/sing-box");

        if (!path.toFile().setExecutable(true)) {
            throw new IOException("无法设置 sing-box 执行权限");
        }

        System.out.println(ANSI_GREEN + "sing-box 下载完成" + ANSI_RESET);
        return path;
    }

    private static String getLatestSingBoxVersion() throws Exception {
        URL url = new URL("https://api.github.com/repos/SagerNet/sing-box/releases/latest");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }

        String json = sb.toString();
        // 简单解析 "tag_name":"v1.x.x"
        int idx = json.indexOf("\"tag_name\"");
        if (idx < 0) throw new RuntimeException("无法获取 sing-box 最新版本");
        int start = json.indexOf("\"", idx + 10) + 1;
        int end   = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    private static void downloadFile(String urlStr, Path dest) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);

        // 跟随重定向
        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_MOVED_PERM
                || status == 307 || status == 308) {
            String newUrl = conn.getHeaderField("Location");
            conn = (HttpURLConnection) new URL(newUrl).openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
        }

        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void extractSingBox(Path tarGz, Path destFile, String entryName) throws Exception {
        // 用系统 tar 命令解压
        Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"), "singbox-extract");
        Files.createDirectories(tmpDir);

        new ProcessBuilder("tar", "xzf", tarGz.toString(), "-C", tmpDir.toString())
                .redirectErrorStream(true)
                .start()
                .waitFor();

        // 找到 sing-box 二进制
        Path extracted = tmpDir.resolve(entryName);
        if (!Files.exists(extracted)) {
            // 尝试递归查找
            extracted = Files.walk(tmpDir)
                    .filter(p -> p.getFileName().toString().equals("sing-box"))
                    .findFirst()
                    .orElseThrow(() -> new IOException("解压后未找到 sing-box 二进制"));
        }
        Files.copy(extracted, destFile, StandardCopyOption.REPLACE_EXISTING);
    }

    // -------- 生成自签名 TLS 证书 --------
    private static void generateCert() throws Exception {
        Path worldDir = Paths.get("./world");
        Files.createDirectories(worldDir);

        Path certPath = worldDir.resolve("cert.pem");
        Path keyPath  = worldDir.resolve("private.key");

        if (Files.exists(certPath) && Files.exists(keyPath)) {
            System.out.println(ANSI_GREEN + "证书已存在，跳过生成" + ANSI_RESET);
            return;
        }

        System.out.println(ANSI_GREEN + "正在生成自签名 TLS 证书..." + ANSI_RESET);

        new ProcessBuilder(
                "openssl", "req", "-x509", "-newkey", "rsa:2048",
                "-keyout", keyPath.toString(),
                "-out",    certPath.toString(),
                "-days",   "3650",
                "-nodes",
                "-subj",   "/CN=bing.com"
        ).redirectErrorStream(true).start().waitFor();

        System.out.println(ANSI_GREEN + "证书生成完成" + ANSI_RESET);
    }

    // -------- 写入 sing-box 配置 --------
    private static void writeSingBoxConfig() throws Exception {
        Path configPath = Paths.get(System.getProperty("java.io.tmpdir"), "sing-box-config.json");

        String config = "{\n"
            + "  \"log\": { \"level\": \"info\" },\n"
            + "  \"inbounds\": [\n"
            + "    {\n"
            + "      \"type\": \"hysteria2\",\n"
            + "      \"tag\": \"hy2-in\",\n"
            + "      \"listen\": \"::\",\n"
            + "      \"listen_port\": " + HY2_PORT + ",\n"
            + "      \"users\": [\n"
            + "        { \"password\": \"" + PASSWORD + "\" }\n"
            + "      ],\n"
            + "      \"tls\": {\n"
            + "        \"enabled\": true,\n"
            + "        \"certificate_path\": \"./world/cert.pem\",\n"
            + "        \"key_path\": \"./world/private.key\"\n"
            + "      }\n"
            + "    }\n"
            + "  ],\n"
            + "  \"outbounds\": [\n"
            + "    { \"type\": \"direct\", \"tag\": \"direct\" }\n"
            + "  ]\n"
            + "}\n";

        Files.write(configPath, config.getBytes());
        System.out.println(ANSI_GREEN + "sing-box 配置已写入" + ANSI_RESET);
    }

    // -------- 启动 sing-box --------
    private static void startSingBox(Path sbPath) throws Exception {
        Path configPath = Paths.get(System.getProperty("java.io.tmpdir"), "sing-box-config.json");

        ProcessBuilder pb = new ProcessBuilder(
                sbPath.toString(), "run", "-c", configPath.toString()
        );
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        singboxProcess = pb.start();
        System.out.println(ANSI_GREEN + "sing-box 已启动" + ANSI_RESET);
    }

    // -------- 获取公网 IP --------
    private static String getPublicIp() {
        String[] services = {
            "https://api.ipify.org",
            "https://ip.sb",
            "https://ifconfig.me"
        };
        for (String service : services) {
            try {
                URL url = new URL(service);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String ip = br.readLine().trim();
                    if (ip != null && !ip.isEmpty()) return ip;
                }
            } catch (Exception ignored) {}
        }
        return "220.158.22.116"; // fallback：XServer 控制面板里看到的固定 IP
    }

    // -------- 写入 sub.txt --------
    private static void writeSubTxt(String ip) throws Exception {
        Path worldDir = Paths.get("./world");
        Files.createDirectories(worldDir);

        String hy2Link = "hy2://" + PASSWORD + "@" + ip + ":" + HY2_PORT
                + "?insecure=1&sni=bing.com#" + NODE_NAME;

        // Base64 编码（订阅格式）
        String encoded = Base64.getEncoder().encodeToString(hy2Link.getBytes());

        Path subPath = worldDir.resolve("sub.txt");
        Files.write(subPath, encoded.getBytes());
        System.out.println(ANSI_GREEN + "sub.txt 已写入: " + subPath + ANSI_RESET);
    }

    // -------- 停止 sing-box --------
    private static void stopSingBox() {
        if (singboxProcess != null && singboxProcess.isAlive()) {
            singboxProcess.destroy();
            System.out.println(ANSI_RED + "sing-box 已停止" + ANSI_RESET);
        }
    }

    // -------- 清屏 --------
    private static void clearConsole() {
        try {
            new ProcessBuilder("clear").inheritIO().start().waitFor();
        } catch (Exception ignored) {}
    }
}
