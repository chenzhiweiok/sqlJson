package io.github.jsonsql.mapping;

import io.github.jsonsql.SqlSpecException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行 → 对象映射，框架内唯一出口。
 *
 * <p>与之前"每次反射 setAccessible"的写法相比，这里按类缓存「列名 → Field」，
 * 单次查询只是一次 HashMap 查找加一次 set，高频查询下差别明显。
 *
 * <p>列名匹配优先级：
 * <ol>
 *   <li>完全相等</li>
 *   <li>忽略大小写相等</li>
 *   <li>下划线转驼峰后相等（user_name → userName）</li>
 * </ol>
 */
public final class BeanMapper {

    /** 类型 → (小写列名或驼峰名 → Field) */
    private static final Map<Class<?>, Map<String, Field>> CACHE = new ConcurrentHashMap<>();

    private BeanMapper() {
    }

    /**
     * 一行 Map 转对象。
     *
     * <p>列不存在对应字段时静默跳过，而不是报错——join 结果里常有多余列，
     * 而且同一个主对象可能被多个查询复用，列集合不完全一致是常态。
     *
     * @param row  列名 → 值，key 大小写不敏感
     * @param type 目标类型，需要无参构造
     * @param <T>  目标类型
     * @return 填充后的对象，字段为 null 表示该列缺失或值为 null
     * @throws SqlSpecException 类型没有无参构造，或字段写入失败
     */
    public static <T> T fromMap(Map<String, Object> row, Class<T> type) {
        T bean = newInstance(type);
        Map<String, Field> fields = fieldsOf(type);
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            Field f = lookup(fields, e.getKey());
            if (f != null) {
                set(f, bean, e.getValue());
            }
        }
        return bean;
    }

    /**
     * 从结果集当前行取数据。
     *
     * <p>按列的 label（不是表名限定名）匹配字段，所以 {@code select u.name as user_name}
     * 也能正确映射到 {@code userName}。取不到的列跳过，不抛异常。
     *
     * <p>调用方负责 {@code rs.next()}：这个方法只处理当前行，不移动游标。
     *
     * @param rs   已定位到目标行的结果集
     * @param type 目标类型
     * @param <T>  目标类型
     * @return 填充后的对象
     * @throws SqlSpecException 读取结果集失败或目标类型无法实例化
     */
    public static <T> T fromRow(ResultSet rs, Class<T> type) {
        T bean = newInstance(type);
        Map<String, Field> fields = fieldsOf(type);
        try {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                Field f = lookup(fields, md.getColumnLabel(i));
                if (f == null) {
                    continue;
                }
                Object v = rs.getObject(i);
                if (v != null) {
                    set(f, bean, v);
                }
            }
            return bean;
        } catch (SQLException e) {
            throw new SqlSpecException("映射到 " + type.getSimpleName() + " 失败: " + e.getMessage());
        }
    }

    /**
     * 结果集当前行转 Map，列名统一小写。
     *
     * <p>统一小写是为了跨库一致：MySQL 返回 {@code USER_NAME} 还是 {@code user_name}
     * 取决于列定义和配置，而下游的 join 组装、前端渲染都按小写 key 取值。
     *
     * @param rs 已定位到目标行的结果集
     * @return 列名（小写）→ 值，保持 SQL 里的列顺序
     * @throws SqlSpecException 读取结果集失败
     */
    public static Map<String, Object> rowToMap(ResultSet rs) {
        try {
            ResultSetMetaData md = rs.getMetaData();
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                row.put(md.getColumnLabel(i).toLowerCase(), rs.getObject(i));
            }
            return row;
        } catch (SQLException e) {
            throw new SqlSpecException("读取结果集失败: " + e.getMessage());
        }
    }

    /**
     * 按字段名写值，主要给 {@link JoinAssembler} 塞子集合用。
     *
     * <p>与 {@link #fromMap} 的静默跳过相反，这里字段找不到会抛异常：
     * 组装器声明的字段名是代码里写死的，找不到就是拼写错误，应该立刻暴露。
     *
     * @param bean      目标对象
     * @param fieldName 字段名，支持列名形式（{@code user_name → userName}）
     * @param value     值，会按字段类型转换
     * @throws SqlSpecException 字段不存在或写入失败
     */
    public static void setByName(Object bean, String fieldName, Object value) {
        Field f = lookup(fieldsOf(bean.getClass()), fieldName);
        if (f == null) {
            throw new SqlSpecException(bean.getClass().getSimpleName() + " 没有字段 " + fieldName);
        }
        set(f, bean, value);
    }

    /**
     * 按字段名读值，主要给组装器与调试用。
     *
     * @param bean      目标对象
     * @param fieldName 字段名（大小写不敏感，支持列名形式）
     * @return 字段值；字段不存在或读取失败时返回 null，不抛异常——
     *         调用方通常只是「有就展示」，不该因为一个可选字段把整个查询打挂
     */
    public static Object getByName(Object bean, String fieldName) {
        Field f = lookup(fieldsOf(bean.getClass()), fieldName);
        if (f == null) {
            return null;
        }
        try {
            f.setAccessible(true);
            return f.get(bean);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static <T> T newInstance(Class<T> type) {
        try {
            Constructor<T> c = type.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new SqlSpecException("无法实例化 " + type.getSimpleName() + "（需要无参构造）");
        }
    }

    private static Map<String, Field> fieldsOf(Class<?> type) {
        return CACHE.computeIfAbsent(type, t -> {
            Map<String, Field> map = new LinkedHashMap<>();
            for (Class<?> c = t; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                        continue;
                    }
                    f.setAccessible(true);
                    map.putIfAbsent(f.getName().toLowerCase(), f);
                    map.putIfAbsent(camel(f.getName()).toLowerCase(), f);
                }
            }
            return map;
        });
    }

    private static Field lookup(Map<String, Field> fields, String column) {
        if (column == null) {
            return null;
        }
        Field f = fields.get(column);
        if (f != null) {
            return f;
        }
        f = fields.get(column.toLowerCase());
        if (f != null) {
            return f;
        }
        return fields.get(camel(column).toLowerCase());
    }

    /** user_name → userName；已无下划线时原样返回 */
    static String camel(String s) {
        if (s.indexOf('_') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean upper = false;
        for (char ch : s.toCharArray()) {
            if (ch == '_') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(ch));
                upper = false;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    static void set(Field field, Object bean, Object value) {
        try {
            field.set(bean, convert(value, field.getType()));
        } catch (IllegalAccessException e) {
            throw new SqlSpecException("写入字段 " + field.getName() + " 失败");
        }
    }

    /**
     * 把 JDBC 返回的值转成字段类型。
     *
     * <p>支持的范围刻意做窄：数字、BigDecimal、布尔、LocalDateTime/LocalDate、String。
     * 其它类型走 {@code toString()} 兜底，而不是抛异常——数据库里的枚举、JSON 列
     * 往往就是当字符串用的。
     *
     * <p>几个容易踩的点：
     * <ul>
     *   <li>{@code MySQL 的 tinyint(1)} 返回 Integer，转 boolean 时按 {@code Boolean.parseBoolean}
     *       会得到 false（"1" 不是 "true"），需要布尔的列请显式转成 int 判断；</li>
     *   <li>{@link java.time.LocalDate} 从字符串解析时只取前 10 位，
     *       兼容 {@code 2026-11-23 13:00:00} 这种带时间的返回。</li>
     * </ul>
     *
     * @param v      源值，可为 null
     * @param target 目标类型
     * @return 转换后的值；源为 null 时返回 null
     */
    public static Object convert(Object v, Class<?> target) {
        if (v == null) {
            return null;
        }
        if (target.isInstance(v)) {
            return v;
        }
        if (target == int.class || target == Integer.class) {
            return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
        }
        if (target == long.class || target == Long.class) {
            return v instanceof Number n ? n.longValue() : Long.parseLong(v.toString());
        }
        if (target == double.class || target == Double.class) {
            return v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString());
        }
        if (target == BigDecimal.class) {
            return v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
        }
        if (target == boolean.class || target == Boolean.class) {
            return v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString());
        }
        if (target == LocalDateTime.class) {
            return v instanceof Timestamp ts ? ts.toLocalDateTime() : LocalDateTime.parse(v.toString());
        }
        if (target == LocalDate.class) {
            return v instanceof java.sql.Date d ? d.toLocalDate() : LocalDate.parse(v.toString().substring(0, 10));
        }
        return v.toString();
    }
}
