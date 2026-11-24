package io.github.jsonsql.meta.statement;

import io.github.jsonsql.meta.EntityDescriptor;

import io.github.jsonsql.engine.SqlV2;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 默认语句生成时共用的片段。
 *
 * <p>抽出来的东西都不复杂，但被六条语句重复引用，散落在各处就容易不一致——
 * 例如主键占位符的注释，一处写 {@code [1] long id}、另一处写 {@code [1] id}，
 * 参数校验时就会莫名报错。
 */
public final class Statements {

    private Statements() {
    }

    /** 新建一个空的语句节点 */
    public static ObjectNode node() {
        return EntityDescriptor.MAPPER.createObjectNode();
    }

    /**
     * 全列清单，供 SELECT 使用。
     *
     * <p>顺序沿用字段声明顺序，不排序——排序会让「加一个字段」变成「整条 SQL 都变了」，
     * 快照对比时噪声很大。
     */
    public static String selectList(EntityDescriptor d) {
        return String.join(", ", d.allColumnNames());
    }

    /** 按行构造 sql 数组；JSON 里可读性靠它保持「一行一句」 */
    public static ArrayNode sql(String... lines) {
        ArrayNode arr = EntityDescriptor.MAPPER.createArrayNode();
        for (String line : lines) {
            arr.add(line);
        }
        return arr;
    }

    /** 列清单数组，整实体注入时用于限制可写列 */
    public static ArrayNode columns(List<String> names) {
        ArrayNode arr = EntityDescriptor.MAPPER.createArrayNode();
        names.forEach(arr::add);
        return arr;
    }

    /**
     * 主键条件子句。
     *
     * <p>注释里的 {@code [1] long id} 不是装饰：{@link SqlV2} 靠它建立「位置 → 参数名/类型」
     * 的映射，缺了会报「没有定义 #1」。所以这条子句必须由一处统一生成。
     */
    public static String byId(EntityDescriptor d) {
        return " WHERE " + d.idColumn() + " = #1   -- [1] long id 主键";
    }
}
