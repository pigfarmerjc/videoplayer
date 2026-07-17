[CmdletBinding()]
param([string]$OutputDirectory)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
    $OutputDirectory = Join-Path $scriptDirectory "..\test-media\pcm"
}

function Get-RequiredTool {
    param([string]$Name)

    $tool = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $tool) {
        throw "Missing $Name. Install FFmpeg and ensure $Name is on PATH, then retry."
    }

    return $tool.Source
}

function Invoke-Ffmpeg {
    param([string[]]$Arguments)

    & $script:Ffmpeg @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "ffmpeg failed to generate a fixture. Exit code: $LASTEXITCODE"
    }
}

function Assert-StreamCodecs {
    param(
        [string]$Path,
        [string[]]$ExpectedAudioCodecs
    )

    $probeJson = & $script:Ffprobe -v error -show_entries stream=codec_type,codec_name -of json $Path
    if ($LASTEXITCODE -ne 0) {
        throw "ffprobe could not read fixture: $Path"
    }

    $streams = ($probeJson | ConvertFrom-Json).streams
    $videoCodecs = @(
        $streams |
            Where-Object { $_.codec_type -eq "video" } |
            ForEach-Object { $_.codec_name }
    )
    $audioCodecs = @(
        $streams |
            Where-Object { $_.codec_type -eq "audio" } |
            ForEach-Object { $_.codec_name }
    )

    if ($videoCodecs.Count -ne 1 -or $videoCodecs[0] -ne "h264") {
        throw "Fixture video codec verification failed: $Path. Actual: $($videoCodecs -join ', ')"
    }

    if (($audioCodecs -join ",") -ne ($ExpectedAudioCodecs -join ",")) {
        throw "Fixture audio codec verification failed: $Path. Actual: $($audioCodecs -join ', ')"
    }
}

$script:Ffmpeg = Get-RequiredTool "ffmpeg"
$script:Ffprobe = Get-RequiredTool "ffprobe"
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

$mkvPcm = Join-Path $OutputDirectory "video-pcm-s24le.mkv"
$movPcm = Join-Path $OutputDirectory "video-pcm-s24le.mov"
$dualTrackMkv = Join-Path $OutputDirectory "video-aac-pcm-s24le.mkv"

$singleTrackInputs = @(
    "-hide_banner", "-loglevel", "error", "-y",
    "-f", "lavfi", "-i", "smptebars=size=640x360:rate=30",
    "-f", "lavfi", "-i", "sine=frequency=1000:sample_rate=48000",
    "-t", "5", "-map", "0:v:0", "-map", "1:a:0",
    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "pcm_s24le", "-shortest"
)

Invoke-Ffmpeg -Arguments ($singleTrackInputs + @($mkvPcm))
Invoke-Ffmpeg -Arguments ($singleTrackInputs + @($movPcm))
Invoke-Ffmpeg -Arguments @(
    "-hide_banner", "-loglevel", "error", "-y",
    "-f", "lavfi", "-i", "smptebars=size=640x360:rate=30",
    "-f", "lavfi", "-i", "sine=frequency=1000:sample_rate=48000",
    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
    "-t", "5", "-map", "0:v:0", "-map", "1:a:0", "-map", "2:a:0",
    "-c:v", "libx264", "-pix_fmt", "yuv420p",
    "-c:a:0", "aac", "-b:a:0", "128k", "-c:a:1", "pcm_s24le", "-shortest",
    $dualTrackMkv
)

Assert-StreamCodecs -Path $mkvPcm -ExpectedAudioCodecs @("pcm_s24le")
Assert-StreamCodecs -Path $movPcm -ExpectedAudioCodecs @("pcm_s24le")
Assert-StreamCodecs -Path $dualTrackMkv -ExpectedAudioCodecs @("aac", "pcm_s24le")

Write-Host "Generated and verified 3 PCM S24 LE fixtures: $OutputDirectory"
