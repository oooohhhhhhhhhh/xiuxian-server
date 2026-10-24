package com.mtxgdn.game.sect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宗门操作的返回值必须是有类型的 DTO，而不是 {@code Map<String, Object>}。
 *
 * <p><b>缺陷形态</b>：宗门模块十来个操作方法原来一律返回
 * {@code Map<String, Object>}，固定两个键 {@code success} / {@code message}。
 * 调用方于是到处写 {@code (boolean) result.get("success")} 和
 * {@code (String) result.get("message")}：</p>
 *
 * <ul>
 *   <li>键名是字符串，写错、漏写只有运行时才发现；</li>
 *   <li>{@code get()} 返回 {@code Object}，强转失败同样是运行时异常；</li>
 *   <li>忘了判空就是 NPE，所以大家又得写
 *       {@code Boolean.TRUE.equals(result.get("success"))} 这种绕圈子的判空；</li>
 *   <li>响应体有哪些字段，没有任何地方能查。</li>
 * </ul>
 *
 * <p>已把 6 个"键集合恰好是 success + message"的方法改成返回
 * {@link SectOperationResult}。需要额外带数据的方法（要返回宗门、申请列表、
 * 战报的方法）各自仍是 Map —— 那是有意的，报告里逐条列了理由。</p>
 */
class SectOperationResultTest {

    private static final Path MEMBERSHIP_DOMAIN =
            Paths.get("src/main/java/com/mtxgdn/game/service/SectMembershipDomain.java");
    private static final Path FACADE =
            Paths.get("src/main/java/com/mtxgdn/game/service/SectService.java");

    /** 已改成 DTO 的方法：键集合恰好是 success + message。 */
    private static final List<String> DTO_METHODS = List.of(
            "applyToSect", "approveApplication", "leaveSect",
            "kickMember", "appointMember", "addContribution");

    @Test
    @DisplayName("DTO 本身有类型化字段，且提供旧调用方风格的访问器")
    void dtoIsTyped() {
        SectOperationResult ok = new SectOperationResult(true, "成功");
        SectOperationResult fail = new SectOperationResult(false, "失败");

        assertTrue(ok.success(), "success 字段应可读");
        assertTrue(ok.isSuccess(), "isSuccess() 是给旧调用方挪用的兼容访问器");
        assertEquals("成功", ok.message());
        assertFalse(fail.success());
        assertEquals("失败", fail.message());
    }

    @Test
    @DisplayName("DTO 的字段名必须与原来的 Map 键一致，否则 HTTP 响应体会变")
    void dtoFieldsMatchTheOldMapKeys() {
        // 序列化出来的 JSON 键由 record 组件名决定，必须还是 success / message
        SectOperationResult sample = new SectOperationResult(true, "msg");
        assertEquals(List.of("success", "message"), List.of(
                "success", "message"),
                "record 的组件名必须仍是 success/message，前端才能拿到同样的字段");

        // 用反射确认组件名（gson 按这个序列化）
        List<String> components = java.util.Arrays.stream(SectOperationResult.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertEquals(List.of("success", "message"), components,
                "record 组件名必须逐字是 success / message");
    }

    @Test
    @DisplayName("6 个操作方法已改用 SectOperationResult 返回")
    void membershipDomainMethodsReturnDto() throws IOException {
        String code = stripComments(Files.readString(MEMBERSHIP_DOMAIN, StandardCharsets.UTF_8));
        for (String method : DTO_METHODS) {
            assertTrue(code.contains("SectOperationResult " + method + "("),
                    method + "() 应返回 SectOperationResult，而不是 Map<String, Object>");
        }
    }

    @Test
    @DisplayName("域类里不再出现 success/message 的 Map 拼装")
    void membershipDomainNoLongerBuildsResultMaps() throws IOException {
        String code = stripComments(Files.readString(MEMBERSHIP_DOMAIN, StandardCharsets.UTF_8));
        assertFalse(code.contains("result.put(\"success\""),
                "还在往 Map 里 put success：这些方法已改用 DTO");
        assertFalse(code.contains("result.put(\"message\""),
                "还在往 Map 里 put message：这些方法已改用 DTO");
        assertFalse(code.contains("Map<String, Object>"),
                "域类里不应再有 Map<String, Object> 返回值");
    }

    @Test
    @DisplayName("门面同样返回 DTO，且没把 DTO 拆回 Map")
    void facadeReturnsDtoWithoutUnwrapping() throws IOException {
        String code = stripComments(Files.readString(FACADE, StandardCharsets.UTF_8));
        for (String method : DTO_METHODS) {
            assertTrue(code.contains("SectOperationResult " + method + "("),
                    "门面的 " + method + "() 应保持返回 SectOperationResult");
            assertFalse(code.contains("Map<String, Object> " + method + "("),
                    "门面不应把 " + method + "() 的返回类型退回 Map");
        }
    }

    @Test
    @DisplayName("调用方不再写 result.get(\"success\") / result.get(\"message\") 这种字符串取值")
    void callersUseTypedAccessors() throws IOException {
        Pattern call = Pattern.compile(
                "sectService\\.(applyToSect|approveApplication|leaveSect|kickMember|appointMember|addContribution)\\(");
        for (Path file : List.of(
                Paths.get("src/main/java/com/mtxgdn/onebot/command/sect/SectCommand.java"),
                Paths.get("src/main/java/com/mtxgdn/rest/GameSectResource.java"),
                Paths.get("src/main/java/com/mtxgdn/websocket/WsSectHandler.java"))) {
            String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = call.matcher(code);
            while (m.find()) {
                int start = m.start();
                // 看调用点之后的 400 个字符里有没有字符串取值
                String after = code.substring(start, Math.min(code.length(), start + 400));
                assertFalse(after.contains(".get(\"success\")"),
                        file.getFileName() + " 里 " + m.group(1)
                                + " 的结果还在用 .get(\"success\") 取值，应改成 .success()");
                assertFalse(after.contains(".get(\"message\")"),
                        file.getFileName() + " 里 " + m.group(1)
                                + " 的结果还在用 .get(\"message\") 取值，应改成 .message()");
            }
        }
    }

    // ==================== 夹具 ====================

    private static String stripComments(String code) {
        String noBlock = code.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder sb = new StringBuilder();
        for (String line : noBlock.split("\\R")) {
            int idx = line.indexOf("//");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }
}
