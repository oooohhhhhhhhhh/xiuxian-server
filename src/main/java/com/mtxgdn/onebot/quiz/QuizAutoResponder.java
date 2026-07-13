package com.mtxgdn.onebot.quiz;

import org.glassfish.grizzly.websockets.WebSocket;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

public class QuizAutoResponder {

    private static QuizAutoResponder instance;
    private final ConcurrentHashMap<String, Timer> groupTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> groupCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WebSocket> groupSockets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> groupSelfIds = new ConcurrentHashMap<>();

    private QuizAutoResponder() {}

    public static synchronized QuizAutoResponder getInstance() {
        if (instance == null) {
            instance = new QuizAutoResponder();
        }
        return instance;
    }

    public void startAutoAnswer(WebSocket conn, String selfId, Long groupId) {
        String groupIdStr = groupId.toString();
        
        if (groupTimers.containsKey(groupIdStr)) {
            stopAutoAnswer(groupIdStr);
        }

        List<QuizQuestion> questions = QuizService.getInstance().getAll();
        if (questions.isEmpty()) {
            sendGroupMsg(conn, selfId, groupId, "题库为空，无法开始答题！");
            return;
        }

        groupCounters.put(groupIdStr, 0);
        groupSockets.put(groupIdStr, conn);
        groupSelfIds.put(groupIdStr, selfId);
        
        sendGroupMsg(conn, selfId, groupId, "开始答题！准备好接收答案...");

        Timer timer = new Timer(true);
        groupTimers.put(groupIdStr, timer);

        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    Integer counter = groupCounters.get(groupIdStr);
                    WebSocket socket = groupSockets.get(groupIdStr);
                    String sid = groupSelfIds.get(groupIdStr);
                    
                    if (counter == null || counter >= questions.size() || socket == null) {
                        stopAutoAnswer(groupIdStr);
                        sendGroupMsg(socket, sid, groupId, "答题结束！");
                        return;
                    }

                    int currentIndex = counter;
                    groupCounters.put(groupIdStr, currentIndex + 1);

                    QuizQuestion question = questions.get(currentIndex);
                    int questionNumber = currentIndex + 1;

                    if (questionNumber == 5) {
                        sendGroupMsg(socket, sid, groupId, 
                            "第 " + questionNumber + " 题：" + removeMarkdown(question.getQuestion()) + "\n\n（本题跳过）");
                    } else {
                        String answer = question.getQuestion() + "\n\n修仙答" + question.getAnswer();
                        sendGroupMsg(socket, sid, groupId, answer);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, 0, 16000);
    }

    public void stopAutoAnswer(String groupId) {
        Timer timer = groupTimers.remove(groupId);
        if (timer != null) {
            timer.cancel();
        }
        groupCounters.remove(groupId);
        groupSockets.remove(groupId);
        groupSelfIds.remove(groupId);
    }

    public boolean isRunning(String groupId) {
        return groupTimers.containsKey(groupId);
    }

    public void shutdown() {
        for (Timer timer : groupTimers.values()) {
            timer.cancel();
        }
        groupTimers.clear();
        groupCounters.clear();
        groupSockets.clear();
        groupSelfIds.clear();
    }

    private void sendGroupMsg(WebSocket socket, String selfId, Long groupId, String message) {
        if (socket == null || selfId == null || groupId == null || message == null) {
            return;
        }
        
        try {
            String json = "{\"action\":\"send_group_msg\",\"params\":{\"group_id\":" + groupId + ",\"message\":\"" + 
                escapeJson(message) + "\"}}";
            socket.send(json);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r");
    }

    private String removeMarkdown(String text) {
        if (text == null) return "";
        return text
                .replaceAll("\\*\\*", "")
                .replaceAll("\\*", "")
                .replaceAll("_", "")
                .replaceAll("`", "")
                .replaceAll("~", "")
                .replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1");
    }
}
