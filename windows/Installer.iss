#ifndef AppVersion
#define AppVersion "0.1.1"
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

[Tasks]
Name: "appledevices"; Description: "Download and install Apple device support (requires internet and administrator approval)"; Flags: unchecked; Check: NeedsAppleDeviceSupport

[Files]
Source: "dist\A Console\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion
Source: "dist\bridge\*"; DestDir: "{app}\bridge"; Flags: recursesubdirs ignoreversion
Source: "..\LICENSE"; DestDir: "{app}"
Source: "..\macos\Tests\Fixtures\sample.gpx"; DestDir: "{app}\samples"

[Icons]
Name: "{group}\A Console"; Filename: "{app}\A Console.exe"

[Code]
const
  AppleDeviceServiceKey = 'SYSTEM\CurrentControlSet\Services\Apple Mobile Device Service';
  AppleInstallerName = 'iTunes64Setup.exe';
  AppleInstallerURL = 'https://secure-appldnld.apple.com/itunes12/142-28289-20261026-6acff6c8-72eb-4045-84eb-fd35239ac645/iTunes64Setup.exe';
  AppleInstallerSHA256 = '221a24203f6182b7dc0e18055b1e9338a0c3c992c7fefe6b3be0460caf29db4d';

var
  AppleDownload: TDownloadWizardPage;

function NeedsAppleDeviceSupport: Boolean;
begin
  Result := not WizardSilent and not RegKeyExists(HKLM, AppleDeviceServiceKey);
end;

procedure InstallAppleDeviceSupport;
var
  ResultCode: Integer;
begin
  AppleDownload.Clear;
  AppleDownload.Add(AppleInstallerURL, AppleInstallerName, AppleInstallerSHA256);
  AppleDownload.Show;
  try
    AppleDownload.Download;
  finally
    AppleDownload.Hide;
  end;

  if not ShellExec('runas', ExpandConstant('{tmp}\') + AppleInstallerName, '',
      ExpandConstant('{tmp}'), SW_SHOWNORMAL, ewWaitUntilTerminated, ResultCode) then
    RaiseException(SysErrorMessage(ResultCode));

  if not RegKeyExists(HKLM, AppleDeviceServiceKey) then
    RaiseException(Format('Apple Mobile Device Service was not found after setup (exit code %d).', [ResultCode]));
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if (CurStep = ssPostInstall) and NeedsAppleDeviceSupport and
      WizardIsTaskSelected('appledevices') then
  begin
    try
      InstallAppleDeviceSupport;
    except
      Log('Apple device setup: ' + GetExceptionMessage);
      MsgBox('A Console was installed. Apple device support could not be set up.' + #13#10 +
        'Install Windows iTunes from Apple before connecting an iPhone.' + #13#10 + #13#10 +
        GetExceptionMessage, mbInformation, MB_OK);
    end;
  end;
end;

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
  AppleDownload := CreateDownloadPage('Apple device support',
    'Downloading the official Windows iTunes installer from Apple.', nil);
  AppleDownload.ShowBaseNameInsteadOfUrl := True;
  WizardForm.Caption := 'A Console {#AppVersion}';
  Repository := TNewButton.Create(WizardForm);
  Repository.Parent := WizardForm;
  Repository.Caption := 'GitHub Repository';
  Repository.SetBounds(ScaleX(12), WizardForm.CancelButton.Top, ScaleX(118), WizardForm.CancelButton.Height);
  Repository.OnClick := @OpenRepository;
end;
