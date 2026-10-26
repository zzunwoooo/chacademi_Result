@echo off
setlocal
set "ROOT=%~dp0"
set "LOG=%ROOT%build-log.txt"
echo START > "%LOG%"
cd /d "%ROOT%chacademy-motion-mod"
call gradlew.bat build --console=plain >> "%LOG%" 2>&1
if errorlevel 1 goto fail
cd /d "%ROOT%chacademy-motion-plugin"
call gradlew.bat build -x test --console=plain >> "%LOG%" 2>&1
if errorlevel 1 goto fail
if not exist "%ROOT%out" mkdir "%ROOT%out"
copy /y "%ROOT%chacademy-motion-mod\build\libs\chacademy-motion-0.3.0.jar" "%ROOT%out\" >> "%LOG%" 2>&1
copy /y "%ROOT%chacademy-motion-plugin\build\libs\chacademy-motion-plugin-0.3.0.jar" "%ROOT%out\" >> "%LOG%" 2>&1
echo ALL_DONE >> "%LOG%"
exit /b 0
:fail
echo ALL_FAILED >> "%LOG%"
exit /b 1
