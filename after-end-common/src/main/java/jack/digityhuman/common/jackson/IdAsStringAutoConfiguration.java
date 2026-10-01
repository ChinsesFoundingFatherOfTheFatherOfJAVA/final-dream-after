package jack.digityhuman.common.jackson;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.util.List;

/**
 * 「id 一律 string」的可选全局序列化（架构总纲 §4 约定的落实工具）。
 *
 * <p><b>默认关闭</b>：开启需配置 {@code common.jackson.id-as-string=true}。全局把所有 Long 序列化成字符串
 * 是危险的——分页 {@code total}、各种 count 也是 Long/long，会被一起变成字符串打爆前端；
 * 因此这里只按<b>字段名</b>匹配：{@code id} 或以 {@code Id} 结尾且声明类型为 Long/long 的属性才转字符串。
 *
 * <p>各工程当前的实际做法是在 DTO 里把 id 字段直接声明为 String（历史约定），所以默认关闭不影响任何现有接口；
 * 新工程/新接口若想省掉手工 String 转换，可开启本配置并把 DTO 的 id 字段声明为 Long。
 * {@code List<Long>} 形态的 id 集合（如 deptIds）不受本配置影响，仍需在 DTO 层自行用 String 承载。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "common.jackson.id-as-string", havingValue = "true")
public class IdAsStringAutoConfiguration {

    @Bean
    public JsonMapperBuilderCustomizer idAsStringCustomizer() {
        return builder -> builder.addModule(new IdAsStringModule());
    }

    static class IdAsStringModule extends SimpleModule {

        IdAsStringModule() {
            setSerializerModifier(new ValueSerializerModifier() {
                @Override
                public List<BeanPropertyWriter> changeProperties(
                        SerializationConfig config, BeanDescription.Supplier beanDesc,
                        List<BeanPropertyWriter> beanProperties) {
                    for (BeanPropertyWriter writer : beanProperties) {
                        if (isIdNamedLong(writer)) {
                            writer.assignSerializer(ToStringSerializer.instance);
                        }
                    }
                    return beanProperties;
                }

                private boolean isIdNamedLong(BeanPropertyWriter writer) {
                    String name = writer.getName();
                    if (!"id".equals(name) && !name.endsWith("Id")) {
                        return false;
                    }
                    JavaType type = writer.getSerializationType();
                    if (type == null) {
                        type = writer.getType();
                    }
                    Class<?> raw = type == null ? null : type.getRawClass();
                    return raw == Long.class || raw == Long.TYPE;
                }
            });
        }
    }
}
