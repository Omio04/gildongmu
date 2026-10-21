# Builds the debug APK from the command line (same JDK Android Studio uses).
$ErrorActionPreference = "Continue"
$env:JAVA_HOME = "$env:ProgramFiles\Android\Android Studio\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Set-Location $PSScriptRoot
& .\gradlew.bat :app:assembleDebug --console=plain 2>&1 | Tee-Object -FilePath "$PSScriptRoot\build.log"
"exit: $LASTEXITCODE"
