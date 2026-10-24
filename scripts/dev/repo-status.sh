#!/bin/sh
# 一次看清仓库坐标：本地 tag、HEAD、远端 tag、分支领先/落后。
#
# 为什么要有这个脚本：这些信息以前靠一条多段命令取，而多段命令里的重定向
# （`2>/dev/null`、`2>&1`、`>/dev/null`）会让前缀白名单彻底失效 —— 应用的分词器
# 遇到重定向就不再切段，`Bash(git:*)` 这类规则一条都命中不了，于是每次都要人工确认。
# 把重定向关进脚本内部，命令行本身变成一条不带重定向的调用，问题从根上消失。
set -u

cd "$(dirname "$0")/../.." || exit 1

echo "=== 本地 tag（最近 5 个）==="
git tag -l | tail -5

echo "=== 坐标 ==="
printf '%-22s %s\n' "HEAD" "$(git rev-parse HEAD)"
for tag in $(git tag -l 'baseline-pre-*' | tail -3); do
  printf '%-22s %s\n' "$tag" "$(git rev-parse "$tag")"
done

echo "=== 远端 tag ==="
meta=$(git ls-remote --tags origin 'baseline-pre-*' 2>/dev/null || true)
if [ -z "$meta" ]; then
  echo "(远端没有 baseline-pre-* tag)"
else
  printf '%s\n' "$meta"
fi

echo "=== 分支 ==="
git status -sb | head -1

echo "=== 工作区 ==="
if [ -z "$(git status --porcelain)" ]; then
  echo "干净"
else
  git status --porcelain
fi
