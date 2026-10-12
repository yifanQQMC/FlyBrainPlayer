@echo off
REM ============================================================
REM  一键推送到 GitHub
REM  用法：在项目根目录双击运行，或 cmd 里执行 publish.bat
REM ============================================================
echo.
echo   FlyBrainPlayer - 发布到 GitHub
echo   ---------------------------------------------
echo.

REM 1. 检查本地仓库
if not exist ".git" (
    echo [错误] 不是 git 仓库，请先运行: git init
    pause
    exit /b 1
)

REM 2. 检查未提交改动
git status --porcelain >nul 2>&1
for /f %%i in ('git status --porcelain ^| find /c "^"') do set CHANGES=%%i
if not "%CHANGES%"=="0" (
    echo [!] 有未提交的改动，是否自动提交？^& pause
    set /p Y=  提交并继续? (Y/n):
    if /i "%Y%"=="n" goto :cancel
    git add -A
    git commit -m "chore: 补充改动"
)

REM 3. 确保远端正确
git remote get-url origin >nul 2>&1
if errorlevel 1 (
    echo [i] 配置远端...
    git remote add origin https://github.com/yifanQQMC/FlyBrainPlayer.git
) else (
    for /f "delims=" %%r in ('git remote get-url origin') do set REMOTE=%%r
    echo [i] 当前远端: !REMOTE!
)

echo.
echo   请先在浏览器创建空仓库（不要勾选 README/LICENSE）:
echo     https://github.com/new
echo.
echo   仓库名填:  FlyBrainPlayer
echo   可见性选:  Public
echo.
echo   创建完成后回到这里按任意键继续...
pause >nul

REM 4. 推送
echo.
echo [i] 推送到 GitHub...
git push -u origin main
if errorlevel 1 (
    echo.
    echo [错误] 推送失败。常见原因:
    echo   1. 远程仓库还没创建 —— 先去 https://github.com/new 建一个
    echo   2. 没有推送权限 —— 检查仓库是否属于 yifanQQMC
    echo   3. 凭据问题 —— 浏览器会弹出登录框，用有权限的账号登录
    echo.
    pause
    exit /b 1
)

echo.
echo   完成！
echo   https://github.com/yifanQQMC/FlyBrainPlayer
echo.
pause
exit /b 0

:cancel
echo 已取消。
pause
exit /b 0
