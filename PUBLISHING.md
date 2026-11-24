# 把 sqlJson 发布到公网 Nexus

先分清两件事：

| 你说的「公网 Nexus」 | 实际做法 | 适合 |
|---|---|---|
| Sonatype 运营的公网仓库（所有人 `mvn` 能直接拉） | **Maven Central / Central Portal** | 想让别人 `implementation 'io.github.xxx:sqljson-core:1.0.0'` |
| 自己搭一台公网可访问的 Nexus 3 | **Nexus Repository Manager + `mvn deploy`** | 公司内网/私有制品，控制权限和可见范围 |

两条路的配置完全不同。下面都给。

## 工程侧已完成（2026-11-23）

以下改动已经落盘，不需要你再做：

| 项 | 状态 |
|---|---|
| groupId → `io.github.chenzhiweiok` | ✅ 父模块和三个子模块全部同步 |
| 父 artifactId → `sqljson-parent` | ✅ 子模块 parent 引用同步 |
| POM 元数据 `url/licenses/developers/scm` | ✅ 已补 |
| Apache-2.0 `LICENSE` | ✅ 已加到工程根目录 |
| release profile（source/javadoc/gpg/central-publishing 0.12.0） | ✅ 已配 |
| demo 跳过发布（`central.skip=true`，连 sources/javadoc/gpg 也跳过） | ✅ 已配 |
| `settings.xml.example` | ✅ 含 central / ossrh / github 三个 server |
| Maven Central 工作流 | ✅ `.github/workflows/publish.yml` |
| GitHub Packages 发布地址 | ✅ 父 POM `distributionManagement` |
| GitHub Packages 工作流 | ✅ `.github/workflows/publish-github-packages.yml` |
| demo 跳过 `mvn deploy` | ✅ `maven.deploy.skip=true` |
| `mvn -P release package` | ✅ core/spring 均生成 jar + sources + javadoc |
| 全量测试 | ✅ 133 个用例通过 |

注意：`sqljson-mybatis` 目前在工作树里是**已删除状态**（`git status` 显示 staged deletion），
所以没有恢复、也不发布。当前发布模块只有 `sqljson-core` 和 `sqljson-spring`。

## 走 Maven Central 时仍需你本人完成

1. **Portal 账号 + namespace 验证**：`io.github.chenzhiweiok` 必须显示 Verified。
2. **GPG 密钥**：本机生成、公钥推到 keyserver、私钥和 passphrase 留在你本机/CI secrets。
3. **Portal User Token**：生成后填入 `~/.m2/settings.xml`（参考 `settings.xml.example`）。

本机前置：Maven 3.9.9 ✅、JDK 17 ✅、**gpg 未安装** ❌、**`~/.m2/settings.xml` 不存在** ❌。

---

# 路线 A：发布到 Maven Central（Sonatype 公网 Nexus）

> 老教程里的 `oss.sonatype.org` / OSSRH 工单系统已经停用（2025-06-30），
> 现在统一走 **Central Publisher Portal**：https://central.sonatype.com

## A1. 注册并验证 namespace

1. 用 GitHub 登录 https://central.sonatype.com
2. Namespaces → Add Namespace → 填 `io.github.chenzhiweiok`
3. 按提示建一个临时公开仓库（名字是系统给的 verification key），或在 Portal 里用 GitHub 登录自动获得
4. 状态变成 **Verified** 再继续

如果用域名：填反向域名 `com.example`，到 DNS 加一条 TXT 记录，值填 Portal 给的 key。校验的是精确域名 `example.com`，不是子域。

## A2. 生成 GPG 密钥

```bash
brew install gnupg
gpg --full-generate-key
# 选 RSA/RSA，4096 位，过期时间可填 0，名字和邮箱要和 developer 信息一致

gpg --list-keys --keyid-format LONG
gpg --keyserver keyserver.ubuntu.com --send-keys <你的KEY_ID>
```

无交互环境（CI）需要 loopback pinentry：

```bash
echo "allow-loopback-pinentry" >> ~/.gnupg/gpg-agent.conf
echo "pinentry-mode loopback"   >> ~/.gnupg/gpg.conf
gpg-connect-agent reloadagent /bye
```

## A3. Portal User Token 与 settings.xml

在 Portal → Account → **Generate User Token** 拿到一对 token（不是登录密码）。

创建 `~/.m2/settings.xml`：

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>__TOKEN_USERNAME__</username>
      <password>__TOKEN_PASSWORD__</password>
    </server>
    <server>
      <id>ossrh</id>
      <username>__GPG_KEY_ID__</username>
      <password>__GPG_PASSPHRASE__</password>
    </server>
  </servers>
</settings>
```

Token 和 passphrase 不要写进 POM，也不要提交到 Git。CI 里用 secret 注入。

## A4. 改 POM

父 POM 的 GAV 和元数据：

```xml
<groupId>io.github.chenzhiweiok</groupId>
<artifactId>sqljson-parent</artifactId>
<version>1.0.0</version>
<packaging>pom</packaging>

<name>sqlJson</name>
<description>用 JSON 声明 SQL 与映射的轻量 JDBC 层</description>
<url>https://github.com/chenzhiweiok/sqlJson</url>

<licenses>
  <license>
    <name>The Apache License, Version 2.0</name>
    <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
  </license>
</licenses>

<developers>
  <developer>
    <name>陈智威</name>
    <url>https://github.com/chenzhiweiok</url>
  </developer>
</developers>

<scm>
  <connection>scm:git:https://github.com/chenzhiweiok/sqlJson.git</connection>
  <developerConnection>scm:git:ssh://git@github.com/chenzhiweiok/sqlJson.git</developerConnection>
  <url>https://github.com/chenzhiweiok/sqlJson</url>
</scm>
```

以上 POM 改动**已经应用到当前工程**，这段仅作原理说明。

已完成的配套项：

- 工程根目录已有 Apache-2.0 `LICENSE`
- 三个子模块的 `<parent><artifactId>` 已同步为 `sqljson-parent`
- demo 模块已设 `<central.skip>true</central.skip>`
- `sqljson-mybatis` 已从工作树删除，暂不发布

加一个 `release` profile（父 POM）：

```xml
<profiles>
  <profile>
    <id>release</id>
    <properties>
      <central.skip>false</central.skip>
    </properties>
    <build>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-source-plugin</artifactId>
          <version>3.3.1</version>
          <executions>
            <execution>
              <id>attach-sources</id>
              <goals><goal>jar-no-fork</goal></goals>
            </execution>
          </executions>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-javadoc-plugin</artifactId>
          <version>3.6.3</version>
          <configuration>
            <doclint>none</doclint>
          </configuration>
          <executions>
            <execution>
              <id>attach-javadocs</id>
              <goals><goal>jar</goal></goals>
            </execution>
          </executions>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-gpg-plugin</artifactId>
          <version>3.2.7</version>
          <executions>
            <execution>
              <id>sign-artifacts</id>
              <phase>verify</phase>
              <goals><goal>sign</goal></goals>
              <configuration>
                <keyname>${gpg.keyname}</keyname>
                <passphraseServerId>ossrh</passphraseServerId>
                <gpgArguments>
                  <arg>--pinentry-mode</arg>
                  <arg>loopback</arg>
                </gpgArguments>
              </configuration>
            </execution>
          </executions>
        </plugin>
        <plugin>
          <groupId>org.sonatype.central</groupId>
          <artifactId>central-publishing-maven-plugin</artifactId>
          <version>0.12.0</version>
          <extensions>true</extensions>
          <configuration>
            <publishingServerId>central</publishingServerId>
            <skipPublishing>${central.skip}</skipPublishing>
            <!-- 第一次发布保持 false，去 Portal 人工确认后再点 Publish -->
            <autoPublish>false</autoPublish>
          </configuration>
        </plugin>
      </plugins>
    </build>
  </profile>
</profiles>
```

demo 模块 `sqljson-demo/pom.xml`：

```xml
<properties>
  <central.skip>true</central.skip>
</properties>
```

## A5. 发布

```bash
mvn -P release clean deploy -Dgpg.keyname=<你的KEY_ID>
```

插件会：

1. 生成每个模块的 pom/jar/sources/javadoc
2. GPG 签名（`.asc`）
3. 生成 md5/sha1/sha256/sha512 校验和
4. 打成 `target/central-publishing/central-bundle.zip`
5. 上传 Portal，状态流转 `PENDING → VALIDATING → VALIDATED`

`autoPublish=false` 时，去 https://central.sonatype.com/publishing/deployments 检查后点 **Publish**。
首次同步到 `repo1.maven.org` 通常还要十几分钟到几小时。

验证：

```bash
mvn dependency:get -Dartifact=io.github.chenzhiweiok:sqljson-core:1.0.0
```

## A6. 常见失败

| 报错 | 原因 |
|---|---|
| `Namespace not allowed` | groupId 没验证，或与 Portal 的 namespace 不一致 |
| `Missing POM metadata` | `url` / `licenses` / `developers` / `scm` 少一项 |
| `No public key` | 公钥没推到 keyserver，等几分钟或换 keyserver |
| `Bundle has content that does NOT have a .pom` | 老版本插件 + Maven 3.10；用 0.12.0 |
| `Cannot publish duplicate` | 1.0.0 已发布过；Central 的 release 不可变，改版本号重发 |

---

# 路线 B：自建公网 Nexus 3

适合「自己控制仓库、只给指定人用」。**不要把管理端口直接裸露在公网**，前面挂 Nginx + TLS + IP 白名单。

## B1. 起服务

```bash
docker volume create nexus-data
docker run -d --name nexus \
  -p 8081:8081 \
  -v nexus-data:/nexus-data \
  sonatype/nexus3
```

初始管理员密码在容器里：

```bash
docker exec -it nexus cat /nexus-data/admin.password
```

## B2. 建仓库

登录 `http://服务器IP:8081` → Repository → Create repository → **maven2 (hosted)**

- Name：`sqljson-releases`
- Version policy：**Release**
- Deployment policy：**Allow redeploy**（正式环境建议 Disable）

再建一个 `sqljson-snapshots`（Snapshot）。

Security → Roles / Users 建一个发布账号，只给它这两个仓库的 `nx-repository-view-maven2-*-add/read` 权限。

## B3. 配置工程

`settings.xml`：

```xml
<settings>
  <servers>
    <server>
      <id>my-nexus</id>
      <username>deploy-user</username>
      <password>******</password>
    </server>
  </servers>
</settings>
```

父 POM：

```xml
<distributionManagement>
  <repository>
    <id>my-nexus</id>
    <url>https://nexus.example.com/repository/sqljson-releases/</url>
  </repository>
  <snapshotRepository>
    <id>my-nexus</id>
    <url>https://nexus.example.com/repository/sqljson-snapshots/</url>
  </snapshotRepository>
</distributionManagement>
```

发布：

```bash
mvn clean deploy
```

### 已备好的无认证上传方式（推荐）

不依赖 `settings.xml` 和 `distributionManagement`，直接逐文件 PUT：

```bash
NEXUS_URL=https://nexus.example.com \
NEXUS_REPO=maven-releases \
NEXUS_USER=deployer \
NEXUS_PASSWORD='******' \
./scripts/deploy-nexus.sh
```

制品已经预生成在：

```
dist/nexus-repo/                                  Nexus 仓库布局
dist/sqljson-1.0.0-nexus-repo.zip                 可上传/解压的整包
```

包含 `sqljson-parent` / `sqljson-core` / `sqljson-spring` 的 poms、jars、sources、javadoc
以及 md5/sha1。脚本已在本地 PUT 服务上跑通 27 个文件，接真实 Nexus 只需换 URL 和账号。

使用者在自己的 `settings.xml` 或 POM 里加：

```xml
<repositories>
  <repository>
    <id>my-nexus</id>
    <url>https://nexus.example.com/repository/sqljson-releases/</url>
  </repository>
</repositories>
```

## B4. 公网暴露的最低要求

- **HTTPS 必须**：Maven 默认会校验，HTTP 还需在 settings 里配 `allowInsecureProtocol`，别这么干
- 管理界面限制来源 IP，或根本不对外
- 开匿名用户只读时，只给 hosted 仓库的 read，不给 admin
- 定期备份 `nexus-data` 卷

---

---

# 路线 C：GitHub Packages（本工程已配好）

## C1. 工程侧配置

父 POM 已加入：

```xml
<distributionManagement>
  <repository>
    <id>github</id>
    <name>GitHub chenzhiweiok Apache Maven Packages</name>
    <url>https://maven.pkg.github.com/chenzhiweiok/sqlJson</url>
  </repository>
</distributionManagement>
```

`mvn deploy` 会发布：

- `io.github.chenzhiweiok:sqljson-parent:1.0.0`（pom）
- `io.github.chenzhiweiok:sqljson-core:1.0.0`（jar）
- `io.github.chenzhiweiok:sqljson-spring:1.0.0`（jar）

`sqljson-demo` 已设 `maven.deploy.skip=true`，不会进包。

## C2. 本地发布

`~/.m2/settings.xml` 加：

```xml
<server>
  <id>github</id>
  <username>chenzhiweiok</username>
  <password>__GITHUB_PAT__</password>
</server>
```

PAT 用 **classic** token，至少勾 `write:packages`；只拉包用 `read:packages`。

然后：

```bash
cd /Users/chenzhiwei/IdeaProjects/sqlJson
mvn -B -DskipTests deploy
```

成功后在 `https://github.com/chenzhiweiok?tab=packages` 能看到三个包。

## C3. CI 自动发布

工作流在 `.github/workflows/publish-github-packages.yml`：

- 推 `v*` tag 或手动触发
- `permissions: packages: write`
- 用 `GITHUB_TOKEN` 认证，不需要把 PAT 放进 secrets

```bash
git tag v1.0.0
git push origin v1.0.0
```

## C4. 客户如何用

客户 POM：

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/chenzhiweiok/sqlJson</url>
  </repository>
</repositories>

<dependency>
  <groupId>io.github.chenzhiweiok</groupId>
  <artifactId>sqljson-core</artifactId>
  <version>1.0.0</version>
</dependency>
```

客户 `~/.m2/settings.xml`：

```xml
<server>
  <id>github</id>
  <username>客户的GitHub用户名</username>
  <password>客户的PAT，需 read:packages</password>
</server>
```

**即使包是 public，Maven 拉 GitHub Packages 也必须带 PAT**，这是它的认证模型，不是配置错了。

## C5. 四个限制

1. **只支持 classic PAT**，fine-grained token 在 Maven registry 上不适用。
2. **artifactId 只能小写字母、数字、连字符**，大写会返回 `422`；本工程已符合。
3. **包跟随仓库权限**：客户没有该仓库的读权限，就拉不到；这也是它对“外部客户”最不友好的地方。
4. **同一版本不要重复发**：客户端会缓存，且 registry 可能拒绝覆盖；发新版请改 `version`。

---

## 我的建议

**客户有 GitHub 账号** → 走 GitHub Packages。工程侧已经配好，只差一个 PAT：

```bash
mvn -B -DskipTests deploy
```

**客户没有 GitHub 账号、只要能下载** → 走自建 Nexus 或静态仓库，给只读 URL 或只读账号。

**开源、要所有人默认能拉** → 走 Maven Central，代价是 namespace + GPG + Portal token。

三条路的工程配置文件都已就位，区别只在“谁有权限拉”和“客户要不要配认证”。
