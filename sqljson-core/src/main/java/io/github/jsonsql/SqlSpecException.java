package io.github.jsonsql;
/**
 * 框架的统一异常出口：规格错误与执行错误都归到这一类。
 *
 * <p>规格错误指「这份 JSON 写得不对」——字段不在白名单、占位符没有对应描述、
 * 模板变量未定义、{@code ident} 位置传了非法值。这类错误在编译阶段就抛出，
 * 不会发出 SQL。执行错误指数据库返回失败，{@link #getCause()} 会是
 * {@link java.sql.SQLException}，便于调用方按错误码分流。
 *
 * <p>继承 {@link RuntimeException}，调用方不必显式捕获；需要区分「写错了」还是
 * 「库报错」时，检查 cause 类型即可。
 *
 * <p>消息里只放语句 id、列名、原因摘要，<b>不拼接参数值</b>——参数里可能是手机号、
 * token 或身份证号，一旦进日志或响应体就是泄露。
 */
public class SqlSpecException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SqlSpecException(String message) {
        super(message);
    }

    /**
     * @param message 面向开发者的原因说明，应包含语句 id 或列名，便于定位
     * @param cause   底层异常，通常是 {@link java.sql.SQLException}
     */
    public SqlSpecException(String message, Throwable cause) {
        super(message, cause);
    }
}
