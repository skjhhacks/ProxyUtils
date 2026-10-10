@echo off
rem Builds dist\BedwarsProxy.jar. Needs a JDK (javac + jar), Java 8 or newer.
setlocal
cd /d "%~dp0"
if exist build rmdir /s /q build
mkdir build\classes
dir /s /b src\main\java\*.java > build\sources.txt
javac -encoding UTF-8 -source 8 -target 8 -d build\classes @build\sources.txt || exit /b 1
if not exist dist mkdir dist
jar cfe dist\BedwarsProxy.jar bwproxy.Main -C build\classes . || exit /b 1
echo Built dist\BedwarsProxy.jar
