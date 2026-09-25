@echo off
REM Windows launch script for the prebuilt JarRemapper distribution.
REM Requires JDK 21 installed and JAVA_HOME set.

setlocal
set SCRIPT_DIR=%~dp0
set LIB_DIR=%SCRIPT_DIR%lib

if "%JAVA_HOME%"=="" (
  set JAVA_BIN=java
) else (
  set JAVA_BIN=%JAVA_HOME%\bin\java.exe
)

set JAVAFX_MODULES=
for %%f in ("%LIB_DIR%\javafx-*-win.jar") do (
  if defined JAVAFX_MODULES (
    set JAVAFX_MODULES=%JAVAFX_MODULES%;%%f
  ) else (
    set JAVAFX_MODULES=%%f
  )
)

%JAVA_BIN% -Dfile.encoding=UTF-8 -Xmx1g ^
  --module-path "%JAVAFX_MODULES%" ^
  --add-modules javafx.controls,javafx.fxml,javafx.graphics ^
  -cp "%LIB_DIR%\*" ^
  io.github.jarremapper.Main %*

endlocal
