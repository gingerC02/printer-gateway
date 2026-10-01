@echo off
set /p USER=请输入你的 GitHub 用户名: 
set REPO=printer-gateway

rem 确保 git 提交身份（仅作用于本仓库）
git config user.email "%USER%@users.noreply.github.com"
git config user.name "%USER%"

git init
git add .
git commit -m "init gateway" || echo (已有提交，跳过)
git branch -M main
git remote remove origin 2>nul
git remote add origin https://github.com/%USER%/%REPO%.git
git push -u origin main

echo.
echo ✅ 已推送到 https://github.com/%USER%/%REPO%
echo 👉 打开该仓库的 Actions 标签，等待 Build Debug APK 跑完（约 3-5 分钟）
echo 👉 完成后在 Artifacts 里下载 app-debug-apk，解压得到 app-debug.apk 装到手机
pause
