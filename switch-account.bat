@echo off
rem Opens the account menu: pick a saved Microsoft account, add a new one, or remove one.
cd /d "%~dp0"
java -jar dist\BedwarsProxy.jar --accounts %*
pause
