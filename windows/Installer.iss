#ifndef AppVersion
#define AppVersion "0.1.0"
#endif
[Setup]
AppId=UnRealConsoleWindows
AppName=UnReal Console
AppVersion={#AppVersion}
DefaultDirName={localappdata}\Programs\UnReal Console
DefaultGroupName=UnReal Console
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
OutputDir=dist
OutputBaseFilename=UnReal-Console-{#AppVersion}-Windows-x64
Compression=lzma2
SolidCompression=yes

[Files]
Source: "dist\UnRealConsole\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion
Source: "dist\bridge\*"; DestDir: "{app}\bridge"; Flags: recursesubdirs ignoreversion
Source: "..\LICENSE"; DestDir: "{app}"
Source: "..\macos\Tests\Fixtures\sample.gpx"; DestDir: "{app}\samples"

[Icons]
Name: "{group}\UnReal Console"; Filename: "{app}\UnRealConsole.exe"
