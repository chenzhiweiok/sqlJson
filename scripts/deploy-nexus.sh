#!/usr/bin/env bash
# 把 dist/nexus-repo 下的制品逐文件 PUT 到 Nexus 3 的 hosted maven2 仓库。
# 用法：
#   NEXUS_URL=https://nexus.example.com \
#   NEXUS_REPO=maven-releases \
#   NEXUS_USER=deployer \
#   NEXUS_PASSWORD='******' \
#   ./scripts/deploy-nexus.sh
#
# 说明：Nexus 3 的 hosted 仓库支持对仓库路径直接 PUT；
# 这里不用 mvn deploy，避免再依赖 distributionManagement 和 settings.xml。

set -euo pipefail

: "${NEXUS_URL:?需要 NEXUS_URL，例如 https://nexus.example.com}"
: "${NEXUS_REPO:?需要 NEXUS_REPO，例如 maven-releases}"
: "${NEXUS_USER:?需要 NEXUS_USER}"
: "${NEXUS_PASSWORD:?需要 NEXUS_PASSWORD}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/dist/nexus-repo"

if [[ ! -d "$SRC" ]]; then
  echo "找不到 $SRC，先执行：mvn -P release package -DskipTests" >&2
  exit 1
fi

BASE="${NEXUS_URL%/}/repository/${NEXUS_REPO}"
COUNT=0

while IFS= read -r -d '' file; do
  rel="${file#"$SRC"/}"
  url="$BASE/$rel"
  code=$(curl -sS --noproxy '*' -u "$NEXUS_USER:$NEXUS_PASSWORD" \
    -X PUT --upload-file "$file" -o /dev/null -w '%{http_code}' "$url")
  case "$code" in
    200|201|204) ;;
    *) echo "失败 $code: $rel" >&2; exit 1 ;;
  esac
  COUNT=$((COUNT + 1))
  printf '%s %s\n' "$code" "$rel"
done < <(find "$SRC" -type f -print0 | sort -z)

echo "完成：$COUNT 个文件 -> $BASE"
echo "验证：curl -sS --noproxy '*' '$BASE/io/github/chenzhiweiok/sqljson-core/1.0.0/sqljson-core-1.0.0.pom' | head"
