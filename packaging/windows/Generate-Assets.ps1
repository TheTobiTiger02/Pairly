[CmdletBinding()]
param(
    # Receives the MSIX image assets.
    [Parameter(Mandatory = $true)]
    [string] $OutputDirectory,
    # Also rewrites the committed app icon (Assets\Pairly.ico, Assets\PairlyMark.svg) and
    # packaging\brand\pairly-mark.svg. Run with this switch after changing the mark below.
    [switch] $UpdateSourceAssets
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName PresentationCore, WindowsBase

# The Pairly mark, on a 100x100 grid: a PC screen partly behind a phone, white on a
# blue-to-violet tile. This file is the single source for every Windows icon and the SVG copies;
# the XAML BrandMark and the Android vectors use the same paths.
$TileRadius = 22
$GradientStart = '#3B82F6'
$GradientEnd = '#7C3AED'
# The PC outline stops short of the phone so it reads as sitting behind it.
$PcPath = 'M44 28 A6 6 0 0 1 50 22 H76 A6 6 0 0 1 82 28 V44 A6 6 0 0 1 76 50 H60'
$PhonePath = 'M28 38 H42 A8 8 0 0 1 50 46 V74 A8 8 0 0 1 42 82 H28 A8 8 0 0 1 20 74 V46 A8 8 0 0 1 28 38 Z'
$HomeDot = @{ X = 35; Y = 72; Radius = 3.5 }

function Get-StrokeWidth {
    param([double] $PixelSize)
    # Thicker strokes keep the outlines legible at taskbar and tray sizes.
    if ($PixelSize -le 20) { return 10 }
    if ($PixelSize -le 32) { return 8.5 }
    return 7
}

function New-Color([string] $hex) { [System.Windows.Media.ColorConverter]::ConvertFromString($hex) }

function New-BrandGradient {
    $brush = [System.Windows.Media.LinearGradientBrush]::new((New-Color $GradientStart), (New-Color $GradientEnd), [System.Windows.Point]::new(0, 0), [System.Windows.Point]::new(100, 100))
    $brush.MappingMode = [System.Windows.Media.BrushMappingMode]::Absolute
    $brush.Freeze()
    $brush
}

# Draws the mark into a 100x100 unit square at the given pixel offset and size.
function Add-Mark {
    param(
        [System.Windows.Media.DrawingContext] $Context,
        [double] $X, [double] $Y, [double] $Size,
        [bool] $Plated
    )

    $scale = $Size / 100.0
    $Context.PushTransform([System.Windows.Media.TranslateTransform]::new($X, $Y))
    $Context.PushTransform([System.Windows.Media.ScaleTransform]::new($scale, $scale))

    $gradient = New-BrandGradient
    if ($Plated) {
        $Context.DrawRoundedRectangle($gradient, $null, [System.Windows.Rect]::new(0, 0, 100, 100), $TileRadius, $TileRadius)
        $ink = [System.Windows.Media.Brushes]::White
    }
    else {
        # Unplated (taskbar, Start on transparent backgrounds): the outlines carry the gradient.
        $ink = $gradient
    }

    $pen = [System.Windows.Media.Pen]::new($ink, (Get-StrokeWidth $Size))
    $pen.StartLineCap = [System.Windows.Media.PenLineCap]::Round
    $pen.EndLineCap = [System.Windows.Media.PenLineCap]::Round
    $pen.LineJoin = [System.Windows.Media.PenLineJoin]::Round
    $Context.DrawGeometry($null, $pen, [System.Windows.Media.Geometry]::Parse($PcPath))
    $Context.DrawGeometry($null, $pen, [System.Windows.Media.Geometry]::Parse($PhonePath))
    $Context.DrawEllipse($ink, $null, [System.Windows.Point]::new($HomeDot.X, $HomeDot.Y), $HomeDot.Radius, $HomeDot.Radius)

    $Context.Pop()
    $Context.Pop()
}

function New-AssetBitmap {
    param(
        [int] $Width, [int] $Height,
        [double] $MarkSize,
        [bool] $Plated = $true,
        [string] $Background
    )

    $visual = [System.Windows.Media.DrawingVisual]::new()
    $context = $visual.RenderOpen()
    if ($Background) {
        $context.DrawRectangle([System.Windows.Media.SolidColorBrush]::new((New-Color $Background)), $null, [System.Windows.Rect]::new(0, 0, $Width, $Height))
    }
    Add-Mark -Context $context -X (($Width - $MarkSize) / 2) -Y (($Height - $MarkSize) / 2) -Size $MarkSize -Plated $Plated
    $context.Close()

    $bitmap = [System.Windows.Media.Imaging.RenderTargetBitmap]::new($Width, $Height, 96, 96, [System.Windows.Media.PixelFormats]::Pbgra32)
    $bitmap.Render($visual)
    $bitmap.Freeze()
    $bitmap
}

function Get-PngBytes([System.Windows.Media.Imaging.BitmapSource] $Bitmap) {
    $encoder = [System.Windows.Media.Imaging.PngBitmapEncoder]::new()
    $encoder.Frames.Add([System.Windows.Media.Imaging.BitmapFrame]::Create($Bitmap))
    $stream = [System.IO.MemoryStream]::new()
    $encoder.Save($stream)
    # The leading comma keeps PowerShell from unrolling the byte array into the pipeline.
    , $stream.ToArray()
}

function Save-Png([System.Windows.Media.Imaging.BitmapSource] $Bitmap, [string] $Path) {
    [System.IO.File]::WriteAllBytes($Path, (Get-PngBytes $Bitmap))
}

# Multi-resolution ICO with PNG-compressed frames, so Windows picks a sharp image for every size.
function Save-Icon([int[]] $Sizes, [string] $Path) {
    $frames = foreach ($size in $Sizes) {
        # Small icons fill the frame; larger ones keep a little margin like other Windows apps.
        $markSize = if ($size -le 32) { $size } else { [Math]::Round($size * 0.94) }
        , (Get-PngBytes (New-AssetBitmap -Width $size -Height $size -MarkSize $markSize))
    }

    $stream = [System.IO.MemoryStream]::new()
    $writer = [System.IO.BinaryWriter]::new($stream)
    $writer.Write([uint16] 0)
    $writer.Write([uint16] 1)
    $writer.Write([uint16] $Sizes.Count)
    $offset = 6 + 16 * $Sizes.Count
    for ($i = 0; $i -lt $Sizes.Count; $i++) {
        $dimension = if ($Sizes[$i] -ge 256) { 0 } else { $Sizes[$i] }
        $writer.Write([byte] $dimension)
        $writer.Write([byte] $dimension)
        $writer.Write([byte] 0)
        $writer.Write([byte] 0)
        $writer.Write([uint16] 1)
        $writer.Write([uint16] 32)
        $writer.Write([uint32] $frames[$i].Length)
        $writer.Write([uint32] $offset)
        $offset += $frames[$i].Length
    }
    foreach ($frame in $frames) { $writer.Write([byte[]] $frame) }
    $writer.Flush()
    [System.IO.File]::WriteAllBytes($Path, $stream.ToArray())
}

function Get-MarkSvg {
    $dot = $HomeDot
    @"
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" role="img" aria-labelledby="title">
  <title id="title">Pairly</title>
  <defs>
    <linearGradient id="tile" x1="0" y1="0" x2="100" y2="100" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="$GradientStart"/>
      <stop offset="1" stop-color="$GradientEnd"/>
    </linearGradient>
  </defs>
  <rect width="100" height="100" rx="$TileRadius" fill="url(#tile)"/>
  <g fill="none" stroke="#fff" stroke-width="7" stroke-linecap="round" stroke-linejoin="round">
    <path d="$PcPath"/>
    <path d="$PhonePath"/>
  </g>
  <circle cx="$($dot.X)" cy="$($dot.Y)" r="$($dot.Radius)" fill="#fff"/>
</svg>
"@
}

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($resolvedOutput) | Out-Null

$assets = @(
    @{ Name = 'StoreLogo.png'; Width = 50; Height = 50; Mark = 50 },
    @{ Name = 'Square44x44Logo.png'; Width = 44; Height = 44; Mark = 44 },
    @{ Name = 'Square150x150Logo.png'; Width = 150; Height = 150; Mark = 100 },
    @{ Name = 'Square310x310Logo.png'; Width = 310; Height = 310; Mark = 200 },
    @{ Name = 'Wide310x150Logo.png'; Width = 310; Height = 150; Mark = 100 },
    @{ Name = 'SplashScreen.png'; Width = 620; Height = 300; Mark = 160 }
)
foreach ($asset in $assets) {
    $bitmap = New-AssetBitmap -Width $asset.Width -Height $asset.Height -MarkSize $asset.Mark
    Save-Png $bitmap ([System.IO.Path]::Combine($resolvedOutput, $asset.Name))
}

# Taskbar, Start list and title bar sizes: plated and unplated variants per target size.
foreach ($size in 16, 24, 32, 48, 256) {
    Save-Png (New-AssetBitmap -Width $size -Height $size -MarkSize $size) ([System.IO.Path]::Combine($resolvedOutput, "Square44x44Logo.targetsize-$size.png"))
    Save-Png (New-AssetBitmap -Width $size -Height $size -MarkSize $size -Plated $false) ([System.IO.Path]::Combine($resolvedOutput, "Square44x44Logo.targetsize-${size}_altform-unplated.png"))
}

if ($UpdateSourceAssets) {
    $repositoryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine($PSScriptRoot, '..', '..'))
    $appAssets = [System.IO.Path]::Combine($repositoryRoot, 'src', 'windows', 'Pairly.Windows', 'Assets')
    $brand = [System.IO.Path]::Combine($repositoryRoot, 'packaging', 'brand')
    [System.IO.Directory]::CreateDirectory($brand) | Out-Null

    Save-Icon -Sizes 16, 20, 24, 32, 40, 48, 64, 256 -Path ([System.IO.Path]::Combine($appAssets, 'Pairly.ico'))
    $svg = Get-MarkSvg
    $utf8 = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText([System.IO.Path]::Combine($appAssets, 'PairlyMark.svg'), $svg, $utf8)
    [System.IO.File]::WriteAllText([System.IO.Path]::Combine($brand, 'pairly-mark.svg'), $svg, $utf8)
    Save-Png (New-AssetBitmap -Width 512 -Height 512 -MarkSize 512) ([System.IO.Path]::Combine($brand, 'pairly-icon-512.png'))
}

Write-Host "Generated Windows package assets in $resolvedOutput"
