package com.mtxgdn.plugin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 在插件主类上标注元数据，无需额外 plugin.json 文件也可识别。
 * <p>
 * 用法示例:
 * <pre>
 * {@literal @}PluginMeta(name = "我的插件", version = "1.0.0", author = "张三")
 * public class MyPlugin implements Plugin {
 *     // ...
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PluginMeta {
    String name();
    String version() default "1.0.0";
    String author() default "匿名";
    String description() default "";
}
