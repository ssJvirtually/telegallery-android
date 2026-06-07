$BUILD_DIR = "E:\telegallery-calude\app\build\outputs\apk\debug"
$DEVICE_IP = "100.125.241.34"
$DEVICE_PORT = "5555"
$ADB_TARGET = "${DEVICE_IP}:${DEVICE_PORT}"

# Ensure build directory exists
if (-not (Test-Path -Path $BUILD_DIR)) {
    Write-Host "Creating build directory: $BUILD_DIR"
    New-Item -ItemType Directory -Path $BUILD_DIR -Force | Out-Null
}

Write-Host "Watching $BUILD_DIR for new APKs..."
Write-Host "Target device: $ADB_TARGET"

$watcher = New-Object System.IO.FileSystemWatcher
$watcher.Path = $BUILD_DIR
$watcher.Filter = "*.apk"
$watcher.NotifyFilter = [System.IO.NotifyFilters]::FileName -bor [System.IO.NotifyFilters]::LastWrite
$watcher.EnableRaisingEvents = $true

# Track last installed file and time to avoid double trigger
$script:LastInstalledFile = ""
$script:LastInstallTime = [DateTime]::MinValue

$action = {
    $path = $Event.SourceEventArgs.FullPath
    $name = $Event.SourceEventArgs.Name
    $changeType = $Event.SourceEventArgs.ChangeType
    
    # Simple debounce logic: check if the same file was handled in the last 3 seconds
    $now = Get-Date
    if ($script:LastInstalledFile -eq $path -and ($now - $script:LastInstallTime).TotalSeconds -lt 3) {
        return
    }
    $script:LastInstalledFile = $path
    $script:LastInstallTime = $now

    Write-Host "`nNew APK event: $name ($changeType) at $(Get-Date -Format 'HH:mm:ss')"

    # Wait for file write to complete
    Start-Sleep -Seconds 3

    # Check connection and attempt to connect
    Write-Host "Connecting to $ADB_TARGET..."
    $connectResult = adb connect $ADB_TARGET
    Write-Host $connectResult

    Write-Host "Installing $name on device..."
    # Execute adb install
    $result = adb -s $ADB_TARGET install -r "$path" 2>&1

    if ($LASTEXITCODE -eq 0) {
        Write-Host "Installed successfully: $name"
    } else {
        Write-Host "Installation failed: $name"
        Write-Host $result
    }
}

$createdEvent = Register-ObjectEvent $watcher "Created" -Action $action
$changedEvent = Register-ObjectEvent $watcher "Changed" -Action $action

Write-Host "Watcher is running in foreground. Press Ctrl+C to stop."
try {
    while ($true) {
        Start-Sleep -Seconds 1
    }
} finally {
    Write-Host "Stopping watcher and unregistering events..."
    Unregister-Event -SourceIdentifier $createdEvent.Name -ErrorAction SilentlyContinue
    Unregister-Event -SourceIdentifier $changedEvent.Name -ErrorAction SilentlyContinue
    $watcher.Dispose()
}
