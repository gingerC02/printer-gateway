#!/usr/bin/env bash
set -e

read -p "GitHub 用户名: " USER
read -s -p "GitHub Personal Access Token (PAT，输入时不可见): " TOKEN
echo ""
REPO="printer-gateway"

# 先建仓库，再写配置（顺序反了会报 fatal: not in a git directory）
git init
git config user.email "${USER}@users.noreply.github.com"
git config user.name "${USER}"

git add .
git commit -m "init gateway" || echo "(已有提交，跳过)"
git branch -M main
git remote remove origin 2>/dev/null || true
git remote add origin "https://${USER}:${TOKEN}@github.com/${USER}/${REPO}.git"
git push -u origin main
git remote set-url origin "https://github.com/${USER}/${REPO}.git" 2>/dev/null || true

echo ""
echo "✅ 已推送到 https://github.com/${USER}/${REPO}"
echo "👉 打开该仓库的 Actions 标签，等待 'Build Debug APK' 跑完（约 3-5 分钟）"
echo "👉 完成后在 Artifacts 里下载 app-debug-apk，解压得到 app-debug.apk 装到手机"
