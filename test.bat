@echo off
rem Runs the offline test suite (fake server + fake client, no network, no account needed).
setlocal
cd /d "%~dp0"
call "%~dp0build.bat" || exit /b 1
mkdir build\test
dir /s /b src\test\java\*.java > build\tests.txt
javac -encoding UTF-8 -source 8 -target 8 -cp build\classes -d build\test @build\tests.txt || exit /b 1
java -cp build\classes;build\test bwproxy.UnitTests || exit /b 1
java -cp build\classes;build\test bwproxy.EndToEndTest || exit /b 1
