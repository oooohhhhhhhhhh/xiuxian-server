package com.mtxgdn.onebot.quiz;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mtxgdn.util.GameLogger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QuizService {

    private static final GameLogger log = GameLogger.getLogger("QuizService");
    private static final String DATA_PATH = "data" + File.separator + "quiz" + File.separator + "questions.json";
    private static final String RESOURCE_PATH = "/data/quiz/questions.json";
    private static final QuizService instance = new QuizService();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Random random = new Random();
    private final List<QuizQuestion> questions = new ArrayList<>();
    
    private String lastQuestion = null;
    private int lastQuestionNumber = 0;

    private static final Pattern ANSWER_PATTERN = Pattern.compile(
            "正确答案是[：:]*\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern OPTION_LINE_PATTERN = Pattern.compile(
            "^\\s*([1-4])\\s*[、.．。\\)）\\]\\:：]\\s*(.+)$");

    private static final Pattern PREFIX_LINE_PATTERN = Pattern.compile(
            "^\\s*(第\\s*\\d+\\s*题|题目(开始|啦)|请听题|开始答题|.*(游戏开始|答题开始)).*$",
            Pattern.CASE_INSENSITIVE);

    private QuizService() {
        load();
    }

    public static QuizService getInstance() {
        return instance;
    }

    private synchronized void load() {
        File file = new File(DATA_PATH);
        if (file.exists()) {
            try (Reader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                QuizQuestion[] arr = gson.fromJson(reader, QuizQuestion[].class);
                if (arr != null) {
                    for (QuizQuestion q : arr) {
                        questions.add(q);
                    }
                }
                log.info("已从本地文件 [" + DATA_PATH + "] 加载 " + questions.size() + " 道题目");
                return;
            } catch (Exception e) {
                log.error("加载题库文件失败: " + e.getMessage() + "，尝试从内置资源加载", e);
            }
        }

        try (InputStream is = QuizService.class.getResourceAsStream(RESOURCE_PATH)) {
            if (is != null) {
                try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                    QuizQuestion[] arr = gson.fromJson(reader, QuizQuestion[].class);
                    if (arr != null) {
                        for (QuizQuestion q : arr) {
                            questions.add(q);
                        }
                    }
                    log.info("已从内置资源 [" + RESOURCE_PATH + "] 加载 " + questions.size() + " 道题目");
                    return;
                }
            } else {
                log.warn("未找到内置资源 [" + RESOURCE_PATH + "]");
            }
        } catch (Exception e) {
            log.error("从内置资源加载题库失败: " + e.getMessage(), e);
        }

        createDefaultQuestions();
        log.warn("使用代码内置默认题目，共 " + questions.size() + " 道");
    }

    private void createDefaultQuestions() {
        questions.clear();
        questions.add(new QuizQuestion(1, "电影《肖申克的救赎》中，典狱长最后的结局是？", 4));
        questions.add(new QuizQuestion(2, "以下哪项属于修仙境界？", 2));
        questions.add(new QuizQuestion(3, "修仙中吸收天地灵气的过程叫？", 1));
    }

    public synchronized void save() {
        try {
            File file = new File(DATA_PATH);
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            try (Writer writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                gson.toJson(questions.toArray(new QuizQuestion[0]), writer);
            }
        } catch (Exception e) {
            log.error("保存题库失败: " + e.getMessage(), e);
        }
    }

    public synchronized void reload() {
        questions.clear();
        load();
    }

    public List<QuizQuestion> getAll() {
        return new ArrayList<>(questions);
    }

    public QuizQuestion getById(int id) {
        for (QuizQuestion q : questions) {
            if (q.getId() == id) {
                return q;
            }
        }
        return null;
    }

    public QuizQuestion getRandom() {
        if (questions.isEmpty()) return null;
        return questions.get(random.nextInt(questions.size()));
    }

    public int size() {
        return questions.size();
    }

    public QuizQuestion findMatch(String message) {
        if (message == null || message.isBlank()) return null;

        String normalized = normalize(message);
        for (QuizQuestion q : questions) {
            String qNorm = normalize(q.getQuestion());
            if (qNorm.isEmpty()) continue;
            if (normalized.contains(qNorm)) return q;
        }

        String[] lines = message.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (PREFIX_LINE_PATTERN.matcher(trimmed).matches()) continue;
            if (OPTION_LINE_PATTERN.matcher(trimmed).matches()) continue;

            String lineNorm = normalize(trimmed);
            if (lineNorm.isEmpty()) continue;

            for (QuizQuestion q : questions) {
                String qNorm = normalize(q.getQuestion());
                if (qNorm.isEmpty()) continue;
                if (lineNorm.equals(qNorm) || lineNorm.contains(qNorm)) {
                    return q;
                }
            }
        }
        return null;
    }

    private String normalize(String text) {
        if (text == null) return "";
        String s = text;
        s = Pattern.compile("\\s+").matcher(s).replaceAll("");
        s = Pattern.compile("[,，。！？、；：\"'（）《》【】『』〖〗·•\\-—_/\\\\]").matcher(s).replaceAll("");
        return s.toLowerCase();
    }

    public synchronized int add(String questionText, int answer) {
        if (questionText == null || questionText.isBlank()) {
            throw new IllegalArgumentException("题干不能为空");
        }
        if (answer < 1 || answer > 4) {
            throw new IllegalArgumentException("答案必须在 1~4 之间");
        }

        int newId = 1;
        for (QuizQuestion q : questions) {
            if (q.getId() >= newId) newId = q.getId() + 1;
        }
        questions.add(new QuizQuestion(newId, questionText, answer));
        save();
        return newId;
    }

    public synchronized boolean delete(int id) {
        for (int i = 0; i < questions.size(); i++) {
            if (questions.get(i).getId() == id) {
                questions.remove(i);
                save();
                return true;
            }
        }
        return false;
    }

    public synchronized void processMessage(String message) {
        if (message == null || message.isBlank()) return;

        String questionText = extractQuestion(message);
        Integer answer = extractAnswer(message);

        if (questionText != null && answer != null) {
            if (lastQuestion != null) {
                if (findMatch(lastQuestion) == null) {
                    add(lastQuestion, answer);
                }
            } else {
                if (findMatch(questionText) == null) {
                    add(questionText, answer);
                }
            }
            lastQuestion = questionText;
            lastQuestionNumber = extractQuestionNumber(message);
        } else if (questionText != null) {
            lastQuestion = questionText;
            lastQuestionNumber = extractQuestionNumber(message);
        } else if (answer != null && lastQuestion != null) {
            if (findMatch(lastQuestion) == null) {
                add(lastQuestion, answer);
            }
            lastQuestion = null;
            lastQuestionNumber = 0;
        }
    }

    private String extractQuestion(String message) {
        String[] lines = message.split("\\r?\\n");
        StringBuilder question = new StringBuilder();
        boolean inQuestion = false;
        int optionCount = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            
            if (PREFIX_LINE_PATTERN.matcher(trimmed).matches()) {
                inQuestion = true;
                question.setLength(0);
                continue;
            }
            
            Matcher optMatcher = OPTION_LINE_PATTERN.matcher(trimmed);
            if (optMatcher.matches()) {
                optionCount++;
                if (optionCount >= 4) {
                    break;
                }
                continue;
            }
            
            if (trimmed.contains("请从下面选择") || trimmed.contains("正确答案")) {
                break;
            }
            
            if (inQuestion || question.length() > 0) {
                String cleaned = trimmed
                        .replaceAll("^[>\\s]+", "")
                        .replaceAll("[*_`~]", "");
                if (!cleaned.isEmpty()) {
                    if (question.length() > 0) {
                        question.append(" ");
                    }
                    question.append(cleaned);
                }
            }
        }

        String result = question.toString().trim();
        if (result.length() > 5) {
            return result;
        }
        return null;
    }

    private Integer extractAnswer(String message) {
        Matcher matcher = ANSWER_PATTERN.matcher(message);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private int extractQuestionNumber(String message) {
        Matcher m = Pattern.compile("第\\s*(\\d+)\\s*题", Pattern.CASE_INSENSITIVE).matcher(message);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }
}
