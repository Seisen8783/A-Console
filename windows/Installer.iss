#ifndef AppVersion
#define AppVersion "0.1.0"
#endif
[Setup]
AppId=UnRealConsoleWindows
AppName=A Console
AppVersion={#AppVersion}
DefaultGroupName=A Console
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
OutputDir=dist
OutputBaseFilename=A-Console-{#AppVersion}-Windows-x64
Compression=lzma2
SolidCompression=yes
WizardResizable=no
SetupIconFile=a-console.ico
UninstallDisplayIcon={app}\A Console.exe
AppPublisherURL=https://github.com/Seisen8783/A-Console
DefaultDirName={localappdata}\Programs\A Console

[Files]
Source: "dist\A Console\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion
Source: "dist\bridge\*"; DestDir: "{app}\bridge"; Flags: recursesubdirs ignoreversion
Source: "..\LICENSE"; DestDir: "{app}"
Source: "..\macos\Tests\Fixtures\sample.gpx"; DestDir: "{app}\samples"

[Icons]
Name: "{group}\A Console"; Filename: "{app}\A Console.exe"

[Code]
procedure OpenRepository(Sender: TObject);
var
  ErrorCode: Integer;
begin
  if not ShellExec('open', 'https://github.com/Seisen8783/A-Console', '', '', SW_SHOWNORMAL, ewNoWait, ErrorCode) then
    MsgBox('Could not open GitHub Repository.', mbError, MB_OK);
end;

procedure InitializeWizard;
var
  Repository: TNewButton;
begin
  WizardForm.Caption := 'A Console {#AppVersion}';
  Repository := TNewButton.Create(WizardForm);
  Repository.Parent := WizardForm;
  Repository.Caption := 'GitHub Repository';
  Repository.SetBounds(ScaleX(12), WizardForm.CancelButton.Top, ScaleX(118), WizardForm.CancelButton.Height);
  Repository.OnClick := @OpenRepository;
end;
