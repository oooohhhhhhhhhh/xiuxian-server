package com.mtxgdn.onebot;

import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;
import org.glassfish.grizzly.websockets.WebSocket;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 截图模拟模式 - 通过截图 + Robot 键鼠模拟来控制 QQ 窗口，实现真人操作效果。
 * <p>
 * 工作流程：
 * <ol>
 *   <li>查找目标窗口（通过窗口标题关键词）</li>
 *   <li>定时截图聊天区域，对比检测新消息</li>
 *   <li>识别消息内容，解析指令</li>
 *   <li>通过 Robot 模拟键盘输入 + 粘贴发送回复</li>
 * </ol>
 * 该模式只使用普通文字方式发送消息（不使用聊天记录/合并转发）。
 */
public class OneBotScreenshotBot implements OneBotMessageSender, Runnable {

    private static final GameLogger log = GameLogger.getLogger("ScreenshotBot");

    private final String windowTitle;
    private final int pollIntervalMs;

    private Robot robot;
    private Rectangle chatBounds;       // 聊天区域坐标
    private Rectangle inputBounds;      // 输入框区域坐标
    private Point sendButtonPos;        // 发送按钮坐标
    private BufferedImage lastScreenshot;
    private BufferedImage scaledScreenshot;  // 降采样用于对比，减少内存
    private volatile boolean running = false;

    /** 截图对比降采样比例（如 4 = 1/4 分辨率） */
    private static final int COMPARE_DOWNSCALE = 4;

    public OneBotScreenshotBot() {
        this.windowTitle = AppConfig.get("onebot.screenshot.window_title", "QQ");
        this.pollIntervalMs = AppConfig.getInt("onebot.screenshot.poll_interval", 2000);
    }

    // ==================== 初始化 ====================

    /**
     * 通过 PowerShell 查找窗口，返回窗口左上角坐标；找不到返回 null。
     */
    private Point findWindow(String titleKeyword) {
        try {
            // 使用 PowerShell 查找包含关键词的窗口进程
            String cmd = "powershell -Command \"Add-Type -Name Window -Namespace Console " +
                    "-MemberDefinition '[DllImport(\\\"user32.dll\\\")] public static extern IntPtr FindWindow(" +
                    "string lpClassName, string lpWindowName); " +
                    "[DllImport(\\\"user32.dll\\\")] public static extern bool GetWindowRect(" +
                    "IntPtr hWnd, out RECT lpRect); " +
                    "[DllImport(\\\"user32.dll\\\")] public static extern bool GetClientRect(" +
                    "IntPtr hWnd, out RECT lpRect); " +
                    "[DllImport(\\\"user32.dll\\\")] public static extern bool ClientToScreen(" +
                    "IntPtr hWnd, ref POINT lpPoint); " +
                    "public struct RECT { public int Left, Top, Right, Bottom; } " +
                    "public struct POINT { public int X, Y; }'; " +
                    "$processes = Get-Process | Where-Object { $_.MainWindowTitle -like '*" +
                    titleKeyword + "*' -and $_.MainWindowTitle -ne '' }; " +
                    "if ($processes) { $h = $processes[0].MainWindowHandle; " +
                    "$rect = New-Object Console.Window+RECT; " +
                    "[Console.Window]::GetWindowRect($h, [ref]$rect); " +
                    "Write-Host ($rect.Left.ToString() + ',' + $rect.Top.ToString() + " +
                    "'|' + $processes[0].MainWindowTitle); } else { Write-Host 'NOT_FOUND'; }\"";
            Process proc = Runtime.getRuntime().exec(new String[]{"cmd", "/c", cmd});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), "GBK"))) {
                String line = reader.readLine();
                proc.waitFor();
                if (line == null || "NOT_FOUND".equals(line)) {
                    return null;
                }
                // 格式: "x,y|窗口标题"
                String[] parts = line.split("\\|");
                if (parts.length > 0) {
                    String[] coords = parts[0].split(",");
                    if (coords.length >= 2) {
                        int x = Integer.parseInt(coords[0]);
                        int y = Integer.parseInt(coords[1]);
                        String foundTitle = parts.length > 1 ? parts[1] : titleKeyword;
                        log.info("找到窗口: \"" + foundTitle + "\" 位置: (" + x + ", " + y + ")");
                        return new Point(x, y);
                    }
                }
            }
        } catch (Exception e) {
            log.error("查找窗口失败: " + e.getMessage(), e);
        }
        return null;
    }

    public boolean init() {
        try {
            robot = new Robot();
            robot.setAutoDelay(50);
        } catch (AWTException e) {
            log.error("初始化 Robot 失败: " + e.getMessage(), e);
            return false;
        }

        log.info("截图模式初始化... 查找窗口关键词: \"" + windowTitle + "\"");

        Point winPos = findWindow(windowTitle);
        if (winPos == null) {
            log.warn("未找到窗口标题包含 \"" + windowTitle + "\" 的窗口，将使用全屏。");
            log.warn("请在配置 onebot.screenshot.window_title 中设置正确的窗口标题关键词");
            log.warn("5 秒后继续使用全屏模式...");
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {}
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            // 使用屏幕下半部分作为聊天区域（保守估计）
            chatBounds = new Rectangle(0, screen.height / 2, screen.width, screen.height / 2);
            inputBounds = new Rectangle(0, screen.height - 100, screen.width, 80);
            sendButtonPos = new Point(screen.width - 60, screen.height - 40);
        } else {
            // 默认布局估算：聊天区在窗口上部 70%，输入区在下部
            Dimension typicalWindow = new Dimension(800, 600);
            chatBounds = new Rectangle(winPos.x + 5, winPos.y + 30,
                    typicalWindow.width - 10, typicalWindow.height - 200);
            inputBounds = new Rectangle(winPos.x + 5, winPos.y + typicalWindow.height - 170,
                    typicalWindow.width - 10, 80);
            sendButtonPos = new Point(winPos.x + typicalWindow.width - 60,
                    winPos.y + typicalWindow.height - 40);
        }
        log.info("截图区域: " + chatBounds);
        log.info("输入区域: " + inputBounds);
        log.info("发送按钮: " + sendButtonPos);
        log.info("截图模式初始化完成，轮询间隔: " + pollIntervalMs + "ms");
        return true;
    }

    // ==================== 主循环 ====================

    @Override
    public void run() {
        running = true;
        log.info("截图模式轮询已启动");

        while (running) {
            try {
                pollOnce();
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("轮询异常: " + e.getMessage(), e);
            }
        }
        log.info("截图模式轮询已停止");
    }

    private void pollOnce() {
        BufferedImage current = robot.createScreenCapture(chatBounds);
        if (lastScreenshot == null) {
            // 保存降采样版本用于对比（大幅减少内存）
            BufferedImage scaled = scaleDown(current, COMPARE_DOWNSCALE);
            releaseImage(lastScreenshot);
            lastScreenshot = scaled;
            scaledScreenshot = scaled;
            current.flush();
            return;
        }

        BufferedImage scaled = scaleDown(current, COMPARE_DOWNSCALE);
        double diffPercent = compareImages(lastScreenshot, scaled);

        // 释放旧图，换上新的降采样图
        releaseImage(lastScreenshot);
        lastScreenshot = scaled;
        scaledScreenshot = scaled;
        current.flush();

        if (diffPercent > 0.5) {
            log.debug("检测到聊天区变化 (差异度: " + String.format("%.2f", diffPercent) + "%)");
            onNewMessageDetected();
        }
    }

    /** 安全释放 BufferedImage 内存 */
    private void releaseImage(BufferedImage img) {
        if (img != null) {
            img.flush();
        }
    }

    /** 降采样：减少像素对比用的内存 */
    private BufferedImage scaleDown(BufferedImage source, int factor) {
        int w = source.getWidth() / factor;
        int h = source.getHeight() / factor;
        if (w < 1) w = 1;
        if (h < 1) h = 1;
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    private double compareImages(BufferedImage img1, BufferedImage img2) {
        if (img1.getWidth() != img2.getWidth() || img1.getHeight() != img2.getHeight()) {
            return 100.0;
        }
        int w = img1.getWidth();
        int h = img1.getHeight();
        int diff = 0;
        int samples = 0;
        // 由于已经降采样，这里不做抽样，直接全量对比（灰度单字节，很快）
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (img1.getRGB(x, y) != img2.getRGB(x, y)) {
                    diff++;
                }
                samples++;
            }
        }
        return samples > 0 ? (100.0 * diff / samples) : 0;
    }

    // ==================== OCR 识别（Windows 系统内置） ====================

    private String lastRecognizedText = "";

    /**
     * 使用 Windows 10+ 内置 OCR 引擎识别图片中的文字。
     * 通过 PowerShell 调用 Windows.Media.Ocr。
     */
    private String recognizeText(BufferedImage image) {
        Path tempFile = null;
        try {
            // 保存截图到临时 PNG 文件
            tempFile = Files.createTempFile("xiuxian_ocr_", ".png");
            File file = tempFile.toFile();
            ImageIO.write(image, "png", file);

            // PowerShell 调用 Windows.Media.Ocr
            String script =
                "[Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType=WindowsRuntime] > $null; " +
                "[Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType=WindowsRuntime] > $null; " +
                "[Windows.Storage.StorageFile, Windows.Foundation, ContentType=WindowsRuntime] > $null; " +
                "$path = '" + file.getAbsolutePath().replace("\\", "\\\\") + "'; " +
                "$file = [Windows.Storage.StorageFile]::GetFileFromPathAsync($path).GetAwaiter().GetResult(); " +
                "$stream = $file.OpenReadAsync().GetAwaiter().GetResult(); " +
                "$decoder = [Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream).GetAwaiter().GetResult(); " +
                "$bitmap = $decoder.GetSoftwareBitmapAsync().GetAwaiter().GetResult(); " +
                "$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages(); " +
                "if ($engine) { " +
                "  $result = $engine.RecognizeAsync($bitmap).GetAwaiter().GetResult(); " +
                "  Write-Host $result.Text; " +
                "} else { Write-Host 'OCR_ENGINE_NULL'; }";

            Process proc = Runtime.getRuntime().exec(
                    new String[]{"powershell", "-NoProfile", "-Command", script});
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), "UTF-8"))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                proc.waitFor();
                String text = sb.toString().trim();
                if (text.isEmpty() || "OCR_ENGINE_NULL".equals(text)) {
                    return "";
                }
                return text;
            }
        } catch (Exception e) {
            log.error("OCR 识别失败: " + e.getMessage());
            return "";
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {}
            }
        }
    }

    // ==================== 消息检测 ====================

    private void onNewMessageDetected() {
        log.debug("检测到聊天区变化，开始 OCR 识别...");
        // 重截聊天区，缩放后 OCR（大幅减少内存和文件大小）
        BufferedImage screenshot = robot.createScreenCapture(chatBounds);
        BufferedImage ocrImage = scaleDown(screenshot, COMPARE_DOWNSCALE);
        screenshot.flush();
        String text = recognizeText(ocrImage);
        ocrImage.flush();
        if (text.isEmpty()) {
            log.debug("OCR 未识别到文字");
            return;
        }

        // 只输出新增的文字（与上次对比）
        if (!text.equals(lastRecognizedText)) {
            String newText = extractNewText(lastRecognizedText, text);
            lastRecognizedText = text;
            if (!newText.isEmpty()) {
                log.info("OCR 识别到新消息: " + newText.substring(0, Math.min(200, newText.length())));
            }
        }
    }

    /**
     * 从两段文本中提取新增部分（后段比前段多出的尾部内容）。
     */
    private String extractNewText(String oldText, String newText) {
        if (oldText.isEmpty()) return newText;
        // 找到公共前缀长度
        int commonLen = 0;
        int maxLen = Math.min(oldText.length(), newText.length());
        for (int i = 0; i < maxLen; i++) {
            if (oldText.charAt(i) == newText.charAt(i)) {
                commonLen++;
            } else {
                break;
            }
        }
        return newText.substring(commonLen).trim();
    }

    // ==================== OneBotMessageSender 实现 ====================

    /**
     * 截图模式只使用普通文字方式发送，不使用聊天记录/合并转发。
     */
    @Override
    public void replyToSource(WebSocket socket, String selfId, String senderQq, Long groupId, String message) {
        // 截图模式下 socket/selfId 参数无实际意义，使用 Robot 发送
        if (groupId != null) {
            // 群聊回复: 输入 "@qq " + 消息
            simulateTyping("[CQ:at,qq=" + senderQq + "] " + message);
        } else {
            simulateTyping(message);
        }
        clickSend();
    }

    @Override
    public void sendPrivateMsg(WebSocket socket, String selfId, String targetQq, String message) {
        simulateTyping(message);
        clickSend();
    }

    @Override
    public void sendGroupMsg(WebSocket socket, String selfId, Long groupId, String message) {
        simulateTyping(message);
        clickSend();
    }

    // ==================== Robot 模拟操作 ====================

    /**
     * 将文本写入剪贴板，然后模拟 Ctrl+V 粘贴 + Enter 发送。
     */
    public void simulateTyping(String message) {
        if (message == null || message.isEmpty()) return;

        // 点击输入框区域，确保焦点在输入框
        int inputCenterX = inputBounds.x + inputBounds.width / 2;
        int inputCenterY = inputBounds.y + inputBounds.height / 2;
        robot.mouseMove(inputCenterX, inputCenterY);
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(100);

        // 清空输入框: Ctrl+A 然后 Backspace
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_A);
        robot.keyRelease(KeyEvent.VK_A);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        robot.delay(50);
        robot.keyPress(KeyEvent.VK_BACK_SPACE);
        robot.keyRelease(KeyEvent.VK_BACK_SPACE);
        robot.delay(50);

        // 写入剪贴板
        StringSelection selection = new StringSelection(message);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
        robot.delay(50);

        // Ctrl+V 粘贴
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        robot.delay(100);

        log.info("已通过模拟粘贴发送消息: " + (message.length() > 50 ? message.substring(0, 50) + "..." : message));
    }

    /**
     * 点击发送按钮 (如果配置了发送按钮坐标)。
     * 如果没有配置发送按钮，使用 Enter 键发送。
     */
    private void clickSend() {
        if (sendButtonPos != null && sendButtonPos.x > 0) {
            robot.mouseMove(sendButtonPos.x, sendButtonPos.y);
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(100);
            log.debug("已点击发送按钮");
        } else {
            // 没有发送按钮坐标，使用 Enter 发送
            robot.keyPress(KeyEvent.VK_ENTER);
            robot.keyRelease(KeyEvent.VK_ENTER);
            robot.delay(100);
            log.debug("已按 Enter 发送");
        }
    }

    /**
     * 模拟输入纯 ASCII 文本（逐键输入，适合短文本）。
     * 对于中文，请使用 {@link #simulateTyping(String)}（剪贴板方式）。
     */
    public void typeAscii(String text) {
        for (char c : text.toCharArray()) {
            int keyCode = KeyEvent.getExtendedKeyCodeForChar(c);
            if (KeyEvent.CHAR_UNDEFINED == keyCode) {
                log.warn("无法键入字符: " + c);
                continue;
            }
            robot.keyPress(keyCode);
            robot.keyRelease(keyCode);
            robot.delay(20);
        }
    }

    // ==================== 生命周期 ====================

    public void start() {
        if (!init()) {
            log.error("截图模式初始化失败");
            return;
        }
        Thread botThread = new Thread(this, "ScreenshotBot");
        botThread.setDaemon(true);
        botThread.start();
    }

    public void stop() {
        running = false;
        releaseImage(lastScreenshot);
        releaseImage(scaledScreenshot);
        lastScreenshot = null;
        scaledScreenshot = null;
    }

    // ==================== 配置区域 ====================

    /**
     * 手动设置聊天区域坐标。
     */
    public void setChatBounds(int x, int y, int width, int height) {
        this.chatBounds = new Rectangle(x, y, width, height);
        log.info("聊天区域已手动设置为: " + chatBounds);
    }

    /**
     * 手动设置输入框区域坐标。
     */
    public void setInputBounds(int x, int y, int width, int height) {
        this.inputBounds = new Rectangle(x, y, width, height);
        log.info("输入区域已手动设置为: " + inputBounds);
    }

    /**
     * 手动设置发送按钮坐标。
     */
    public void setSendButtonPos(int x, int y) {
        this.sendButtonPos = new Point(x, y);
        log.info("发送按钮已手动设置为: " + sendButtonPos);
    }

    /**
     * 获取 Robot 实例（用于自定义操作）。
     */
    public Robot getRobot() {
        return robot;
    }

    /**
     * 测试方法：模拟发送一条消息。
     */
    public void testSend(String message) {
        log.info("测试发送: " + message);
        simulateTyping(message);
        clickSend();
    }
}
