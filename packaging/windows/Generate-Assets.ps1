[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $OutputDirectory
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($resolvedOutput) | Out-Null

function New-LinkedScreenAsset {
    param(
        [Parameter(Mandatory = $true)] [string] $Path,
        [Parameter(Mandatory = $true)] [int] $Width,
        [Parameter(Mandatory = $true)] [int] $Height,
        [switch] $OpaqueBackground
    )

    $bitmap = [System.Drawing.Bitmap]::new($Width, $Height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)

    try {
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality

        if ($OpaqueBackground) {
            $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml('#F3F6FB'))
        }
        else {
            $graphics.Clear([System.Drawing.Color]::Transparent)
        }

        $side = [Math]::Min($Width, $Height)
        $iconSize = [Math]::Max(20.0, $side * 0.58)
        $originX = ($Width - $iconSize) / 2.0
        $originY = ($Height - $iconSize) / 2.0
        $stroke = [Math]::Max(2.0, $iconSize * 0.065)

        $accent = [System.Drawing.ColorTranslator]::FromHtml('#2563EB')
        $accentSoft = [System.Drawing.ColorTranslator]::FromHtml('#DCE8FF')
        $ink = [System.Drawing.ColorTranslator]::FromHtml('#183153')

        $softBrush = [System.Drawing.SolidBrush]::new($accentSoft)
        $accentBrush = [System.Drawing.SolidBrush]::new($accent)
        $accentPen = [System.Drawing.Pen]::new($accent, $stroke)
        $inkPen = [System.Drawing.Pen]::new($ink, $stroke)
        $accentPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
        $accentPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round

        try {
            $desktopX = $originX
            $desktopY = $originY + ($iconSize * 0.08)
            $desktopW = $iconSize * 0.68
            $desktopH = $iconSize * 0.49
            $corner = $iconSize * 0.08

            $desktopPath = [System.Drawing.Drawing2D.GraphicsPath]::new()
            try {
                $diameter = $corner * 2
                $desktopPath.AddArc($desktopX, $desktopY, $diameter, $diameter, 180, 90)
                $desktopPath.AddArc($desktopX + $desktopW - $diameter, $desktopY, $diameter, $diameter, 270, 90)
                $desktopPath.AddArc($desktopX + $desktopW - $diameter, $desktopY + $desktopH - $diameter, $diameter, $diameter, 0, 90)
                $desktopPath.AddArc($desktopX, $desktopY + $desktopH - $diameter, $diameter, $diameter, 90, 90)
                $desktopPath.CloseFigure()
                $graphics.FillPath($softBrush, $desktopPath)
                $graphics.DrawPath($inkPen, $desktopPath)
            }
            finally {
                $desktopPath.Dispose()
            }

            $standY = $desktopY + $desktopH + ($iconSize * 0.12)
            $graphics.DrawLine($inkPen, $desktopX + ($desktopW * 0.5), $desktopY + $desktopH, $desktopX + ($desktopW * 0.5), $standY)
            $graphics.DrawLine($inkPen, $desktopX + ($desktopW * 0.31), $standY, $desktopX + ($desktopW * 0.69), $standY)

            $phoneW = $iconSize * 0.31
            $phoneH = $iconSize * 0.64
            $phoneX = $originX + ($iconSize * 0.64)
            $phoneY = $originY + ($iconSize * 0.27)
            $phoneRadius = $iconSize * 0.07
            $phonePath = [System.Drawing.Drawing2D.GraphicsPath]::new()
            try {
                $diameter = $phoneRadius * 2
                $phonePath.AddArc($phoneX, $phoneY, $diameter, $diameter, 180, 90)
                $phonePath.AddArc($phoneX + $phoneW - $diameter, $phoneY, $diameter, $diameter, 270, 90)
                $phonePath.AddArc($phoneX + $phoneW - $diameter, $phoneY + $phoneH - $diameter, $diameter, $diameter, 0, 90)
                $phonePath.AddArc($phoneX, $phoneY + $phoneH - $diameter, $diameter, $diameter, 90, 90)
                $phonePath.CloseFigure()
                $graphics.FillPath($accentBrush, $phonePath)
            }
            finally {
                $phonePath.Dispose()
            }

            $dotSize = [Math]::Max(1.5, $iconSize * 0.035)
            $graphics.FillEllipse([System.Drawing.Brushes]::White, $phoneX + (($phoneW - $dotSize) / 2), $phoneY + $phoneH - ($iconSize * 0.09), $dotSize, $dotSize)

            $linkY = $originY + ($iconSize * 0.22)
            $graphics.DrawArc($accentPen, $originX + ($iconSize * 0.38), $linkY, $iconSize * 0.34, $iconSize * 0.25, 205, 130)
        }
        finally {
            $softBrush.Dispose()
            $accentBrush.Dispose()
            $accentPen.Dispose()
            $inkPen.Dispose()
        }

        $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    }
    finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

$assets = @(
    @{ Name = 'StoreLogo.png'; Width = 50; Height = 50; Opaque = $false },
    @{ Name = 'Square44x44Logo.png'; Width = 44; Height = 44; Opaque = $false },
    @{ Name = 'Square150x150Logo.png'; Width = 150; Height = 150; Opaque = $false },
    @{ Name = 'Square310x310Logo.png'; Width = 310; Height = 310; Opaque = $false },
    @{ Name = 'Wide310x150Logo.png'; Width = 310; Height = 150; Opaque = $false },
    @{ Name = 'SplashScreen.png'; Width = 620; Height = 300; Opaque = $true }
)

foreach ($asset in $assets) {
    New-LinkedScreenAsset `
        -Path ([System.IO.Path]::Combine($resolvedOutput, $asset.Name)) `
        -Width $asset.Width `
        -Height $asset.Height `
        -OpaqueBackground:$asset.Opaque
}

$iconPng = [System.IO.Path]::Combine($resolvedOutput, '.SmartphoneLink.icon.png')
$iconPath = [System.IO.Path]::Combine($resolvedOutput, 'SmartphoneLink.ico')
New-LinkedScreenAsset -Path $iconPng -Width 256 -Height 256
$iconBytes = [System.IO.File]::ReadAllBytes($iconPng)
$iconStream = [System.IO.File]::Create($iconPath)
$iconWriter = [System.IO.BinaryWriter]::new($iconStream)
try {
    # ICO header and one 256px PNG-compressed image entry. A zero width/height byte denotes 256.
    $iconWriter.Write([UInt16] 0)
    $iconWriter.Write([UInt16] 1)
    $iconWriter.Write([UInt16] 1)
    $iconWriter.Write([Byte] 0)
    $iconWriter.Write([Byte] 0)
    $iconWriter.Write([Byte] 0)
    $iconWriter.Write([Byte] 0)
    $iconWriter.Write([UInt16] 1)
    $iconWriter.Write([UInt16] 32)
    $iconWriter.Write([UInt32] $iconBytes.Length)
    $iconWriter.Write([UInt32] 22)
    $iconWriter.Write($iconBytes)
}
finally {
    $iconWriter.Dispose()
    $iconStream.Dispose()
    Remove-Item -LiteralPath $iconPng -Force
}

Write-Host "Generated Windows package assets in $resolvedOutput"
