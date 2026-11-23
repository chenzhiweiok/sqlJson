#!/usr/bin/env bash
# 启动用户关系系统。
#
# 为什么要清掉 SERVER__PORT：某些宿主环境（IDE / 沙箱）会注入这个变量，
# Spring Boot 的宽松绑定会把它当成 server.port，优先级高于 application.yml，
# 于是配置的 8077 被覆盖成随机端口，表现为「启动成功但 8077 打不开」。
set -e
cd "$(dirname "$0")"

JAR=target/user-relation.jar
if [ ! -f "$JAR" ]; then
  echo "未找到 $JAR，先执行：mvn -q -pl sqljson-demo -am clean package"
  exit 1
fi

exec env -u SERVER__PORT -u SERVER_PORT java -jar "$JAR" "$@"
