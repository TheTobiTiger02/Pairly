# HFP probe

Checks whether Windows lets Pairly act as the Bluetooth hands-free device for a phone
(call audio on the PC). See docs/calls.md for the result.

Needs Developer Mode (Settings > System > For developers). To run:

1. `dotnet publish -c Release -r win-x64 --self-contained false -o out`
2. Copy `AppxManifest.xml` into `out`, plus three PNG logos named as in the manifest.
3. `Add-AppxPackage -Register out\AppxManifest.xml`
4. `slk-hfpprobe` (read-only), `slk-hfpprobe connect S25`, `slk-hfpprobe undo S25`
5. `Get-AppxPackage Pairly.HfpProbe | Remove-AppxPackage`
