from PyInstaller.utils.hooks import collect_all

datas, binaries, hiddenimports = collect_all("pymobiledevice3")
for package in ("pytcp", "qh3"):
    extra_data, extra_binaries, extra_imports = collect_all(package)
    datas += extra_data
    binaries += extra_binaries
    hiddenimports += extra_imports

a = Analysis(
    ["../macos/Sources/UnRealConsole/Resources/device_bridge.py"],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
)
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, [], exclude_binaries=True, name="UnRealDeviceBridge",
          console=True, runtime_hooks=[])
coll = COLLECT(exe, a.binaries, a.datas, name="bridge")
