@echo off
setlocal
set "ROOT=%~dp0"
echo [1/2] Building mod...
cd /d "%ROOT%chacademy-motion-mod"
call gradlew.bat build
if errorlevel 1 goto fail
echo [2/2] Building plugin...
cd /d "%ROOT%chacademy-motion-plugin"
call gradlew.bat build -x test
if errorlevel 1 goto fail
if not exist "%ROOT%out" mkdir "%ROOT%out"
copy /y "%ROOT%chacademy-motion-mod\build\libs\chacademy-motion-0.3.0.jar" "%ROOT%out\" >nul
copy /y "%ROOT%chacademy-motion-plugin\build\libs\chacademy-motion-plugin-0.3.0.jar" "%ROOT%out\" >nul
echo.
echo DONE: two jar files are in the "out" folder.
pause
exit /b 0
:fail
echo.
echo BUILD FAILED. Send the error message above to Claude.
pause
exit /b 1
