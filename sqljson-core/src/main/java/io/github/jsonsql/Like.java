package io.github.jsonsql;
/**
 * 模糊查询的值对象：把「匹配方式」和「转义」两件事从业务代码里收走。
 *
 * <p>为什么需要它。写 {@code LIKE #{keyword}} 时，业务代码通常长这样：
 * <pre>{@code
 * args.put("keyword", "%" + keyword + "%");
 * }</pre>
 * 这行代码有三个问题，而且都不会报错：
 * <ol>
 *   <li><b>拼错位置</b>：少一个 {@code %} 就变成精确匹配，查询结果变空，看起来像「没数据」；</li>
 *   <li><b>不转义</b>：用户输入 {@code 50%} 时，{@code %} 成了通配符，
 *       匹配到一堆无关数据；输全 {@code %} 更是直接全表扫描；</li>
 *   <li><b>模式丢失</b>：前端搜索框想要「前缀匹配」以便走索引，但传过来只是一个字符串，
 *       引擎无从判断。</li>
 * </ol>
 *
 * <p>用这个类型后，调用方只表达意图，转义与包装交给框架：
 * <pre>{@code
 * args.put("keyword", Like.contains("50%"));   // → %50\%%    匹配字面「50%」
 * args.put("keyword", Like.prefix("张"));      // → 张%       可用索引
 * args.put("keyword", Like.raw("张%"));        // → 张%       自己负责转义
 * }</pre>
 *
 * <p><b>转义符是 {@code \}</b>，与 MySQL / PostgreSQL / H2 的 LIKE 默认值一致。
 * Oracle 默认没有转义符，需要在 SQL 里显式写 {@code LIKE ? ESCAPE '\'}；
 * 达梦与 Oracle 一致。这一点目前不由引擎自动处理，跨库时要留意。
 *
 * <p><b>为什么 {@code raw} 要单独开一个口子</b>：有些场景的模式串确实是提前拼好的
 * （例如从配置读的规则、或后端按业务生成的通配表达式）。这时再转义一次会把 {@code %}
 * 变成字面量，反而错。给一个显式出口，比让引擎去猜「这个 % 是想通配还是想匹配字面」可靠。
 */
public record Like(String value, Mode mode) {

    /** 匹配方式 */
    public enum Mode {
        /** 包含：{@code %x%}，最常用，但无法使用 B+Tree 索引 */
        CONTAINS,
        /** 前缀：{@code x%}，可以使用索引，搜索框与自动补全首选 */
        PREFIX,
        /** 后缀：{@code %x}，无法使用索引，仅用于「按后缀找文件」这类场景 */
        SUFFIX,
        /** 原样：不转义、不包装，调用方自己负责 */
        RAW
    }

    public Like {
        if (value == null) {
            throw new IllegalArgumentException("Like 的值不能为 null；要表达「不加条件」请直接把 null 传进参数");
        }
    }

    /** 包含匹配 */
    public static Like contains(String value) {
        return new Like(value, Mode.CONTAINS);
    }

    /** 前缀匹配（能走索引） */
    public static Like prefix(String value) {
        return new Like(value, Mode.PREFIX);
    }

    /** 后缀匹配 */
    public static Like suffix(String value) {
        return new Like(value, Mode.SUFFIX);
    }

    /** 已经是完整模式串，不处理 */
    public static Like raw(String pattern) {
        return new Like(pattern, Mode.RAW);
    }

    /**
     * 转成可直接绑定的模式串。
     *
     * <p>转义规则：{@code \}、{@code %}、{@code _} 前各加一个反斜杠，
     * 这样用户输入里的通配符只按字面匹配。
     *
     * @return 形如 {@code %50\%%} 的模式串
     */
    public String toPattern() {
        if (mode == Mode.RAW) {
            return value;
        }
        String escaped = escape(value);
        return switch (mode) {
            case CONTAINS -> "%" + escaped + "%";
            case PREFIX -> escaped + "%";
            case SUFFIX -> "%" + escaped;
            default -> escaped;
        };
    }

    /**
     * 转义 LIKE 的通配符。
     *
     * <p>顺序很重要：先处理反斜杠，否则后面加进去的反斜杠会被再转一次，
     * 变成 {@code \\%}（匹配「反斜杠 + 任意字符」），与预期相反。
     *
     * @param s 原始输入
     * @return 转义后的字符串
     */
    public static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return mode + "(" + value + ")";
    }
}
