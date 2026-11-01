@echo off
call "%~dp0..\gradlew.bat" -p "%~dp0." %*
exit /b %ERRORLEVEL%
