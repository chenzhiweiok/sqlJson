-- 密码均为 123456（SHA-256("sqljson-demo" + 明文)）
insert into sys_user (user_name, email, dept, status, password_hash, role)
values ('邱老师', 'qiu@demo.com', '技术部', 'ACTIVE',
        'b697720d47ceb78e463881bde6189d74f16348c5d3e5113adb29ddb07c93dc21', 'ADMIN');
insert into sys_user (user_name, email, dept, status, password_hash, role)
values ('张三', 'zhangsan@demo.com', '技术部', 'ACTIVE',
        'b697720d47ceb78e463881bde6189d74f16348c5d3e5113adb29ddb07c93dc21', 'MEMBER');
insert into sys_user (user_name, email, dept, status, password_hash, role)
values ('李四', 'lisi@demo.com', '产品部', 'ACTIVE',
        'b697720d47ceb78e463881bde6189d74f16348c5d3e5113adb29ddb07c93dc21', 'MEMBER');

insert into sys_relation (user_id, target_id, rel_type) values (1, 2, 'FRIEND');
