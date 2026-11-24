package com.demo.relation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端冒烟：登录 → 权限 → 逻辑删除 → 回收站 → 恢复。
 *
 * <p>用 H2 内存库跑，不依赖远程 MySQL；SQL 全部来自 resources/sqls 下的 JSON，
 * 所以这个测试同时验证了「JSON 语句 + Spring 注入 + 拦截器」三条链路是通的。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Spring Boot 集成：登录 / 权限 / 逻辑删除 / 回收站")
class DemoApiTest {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper();

    private MockHttpSession login(String user, String pwd) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"" + user + "\",\"password\":\"" + pwd + "\"}")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userName").value(user));
        return session;
    }

    @Test
    @Order(1)
    @DisplayName("未登录访问接口返回 401")
    void anonymous() throws Exception {
        mvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.msg").value("请先登录"));
    }

    @Test
    @Order(2)
    @DisplayName("密码错误返回 401，且不暴露账号是否存在")
    void wrongPassword() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"张三\",\"password\":\"bad\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.msg").value("用户名或密码不正确"));
    }

    @Test
    @Order(3)
    @DisplayName("登录后可以分页查询，字段包含角色")
    void listAfterLogin() throws Exception {
        MockHttpSession s = login("邱老师", "123456");
        mvc.perform(get("/api/users").param("page", "1").param("size", "2").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.list[0].role").value("ADMIN"));
    }

    @Test
    @Order(4)
    @DisplayName("成员没有删除权限")
    void memberCannotDelete() throws Exception {
        MockHttpSession s = login("张三", "123456");
        mvc.perform(delete("/api/users/3").session(s))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.msg").value("需要管理员权限"));
    }

    @Test
    @Order(5)
    @DisplayName("管理员删除走逻辑删除：列表减少、回收站可见、可恢复")
    void softDeleteAndRestore() throws Exception {
        MockHttpSession admin = login("邱老师", "123456");

        mvc.perform(delete("/api/users/3").session(admin)).andExpect(status().isOk());

        mvc.perform(get("/api/users").session(admin))
                .andExpect(jsonPath("$.total").value(2));

        MvcResult recycle = mvc.perform(get("/api/users/recycle").session(admin))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = json.readTree(recycle.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(1, rows.size(), "回收站里应有 1 条");
        assertEquals("李四", rows.get(0).get("user_name").asText());
        assertEquals("邱老师", rows.get(0).get("updated_by").asText(), "审计字段应记录操作人");

        mvc.perform(post("/api/users/3/restore").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mvc.perform(get("/api/users").session(admin))
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    @Order(6)
    @DisplayName("有关系的用户不能直接删")
    void deleteBlockedByRelation() throws Exception {
        MockHttpSession admin = login("邱老师", "123456");
        mvc.perform(delete("/api/users/2").session(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("条关系")));
    }

    @Test
    @Order(7)
    @DisplayName("统计接口能返回部门与关系分布")
    void stats() throws Exception {
        MockHttpSession s = login("邱老师", "123456");
        MvcResult r = mvc.perform(get("/api/stats").session(s))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(node.get("depts").size() >= 2, "至少两个部门，实际: " + node.get("depts"));
        assertTrue(node.get("types").size() >= 1, "至少一种关系类型");
    }

    @Test
    @Order(8)
    @DisplayName("countBy 接口：与列表 total 同口径，非法状态被拒")
    void countByStatus() throws Exception {
        MockHttpSession s = login("邱老师", "123456");

        mvc.perform(get("/api/users/count").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3));

        mvc.perform(get("/api/users/count").param("status", "ACTIVE").session(s))
                .andExpect(jsonPath("$.count").value(3));

        mvc.perform(get("/api/users/count").param("status", "inactive").session(s))
                .andExpect(jsonPath("$.count").value(0));

        mvc.perform(get("/api/users/count").param("status", "BANNED").session(s))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(9)
    @DisplayName("级联删除：连关系一起删，一个事务内完成")
    void cascadeDelete() throws Exception {
        MockHttpSession admin = login("邱老师", "123456");

        // 张三(id=2) 有一条关系，普通删除会被拒（见 Order(6)），级联删除应成功
        mvc.perform(delete("/api/users/2/cascade").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mvc.perform(get("/api/users").session(admin))
                .andExpect(jsonPath("$.total").value(2));

        MvcResult graph = mvc.perform(get("/api/graph").session(admin))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode edges = json.readTree(graph.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("edges");
        assertEquals(0, edges.size(), "关系应随用户一起被删，实际: " + edges);
    }
}
