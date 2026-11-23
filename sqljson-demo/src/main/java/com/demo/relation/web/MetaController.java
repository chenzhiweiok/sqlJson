package com.demo.relation.web;

import com.demo.relation.auth.SessionUser;
import com.demo.relation.config.AuthInterceptor;
import com.demo.relation.service.RelationService;
import com.demo.relation.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 页面初始化需要的下拉数据，一次取完，避免前端连发三个请求 */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    private final UserService users;
    private final RelationService relations;

    public MetaController(UserService users, RelationService relations) {
        this.users = users;
        this.relations = relations;
    }

    @GetMapping
    public Map<String, Object> meta(HttpSession session) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("types", RelationService.TYPES);

        List<String> depts = new ArrayList<>();
        for (Map<String, Object> row : users.deptStats()) {
            Object d = row.get("dept");
            if (d != null) {
                depts.add(String.valueOf(d));
            }
        }
        body.put("depts", depts);

        SessionUser user = (SessionUser) session.getAttribute(AuthInterceptor.SESSION_KEY);
        if (user != null) {
            body.put("user", user);
        }
        return body;
    }
}
