param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ArgsList
)

$ErrorActionPreference = "Stop"

$inFile = $null
$outFile = $null
$metaFile = $null
$diagFile = $null
$textFallback = "SUBSTITUTE"
$resources = New-Object System.Collections.Generic.List[string]

$i = 0
while ($i -lt $ArgsList.Count) {
    $arg = $ArgsList[$i]
    switch ($arg) {
        "--in" {
            $inFile = $ArgsList[$i + 1]
            $i += 2
        }
        "--out" {
            $outFile = $ArgsList[$i + 1]
            $i += 2
        }
        "--meta" {
            $metaFile = $ArgsList[$i + 1]
            $i += 2
        }
        "--diag" {
            $diagFile = $ArgsList[$i + 1]
            $i += 2
        }
        "--resources" {
            $resources.Add($ArgsList[$i + 1])
            $i += 2
        }
        "--textFallback" {
            $textFallback = $ArgsList[$i + 1]
            $i += 2
        }
        default {
            Write-Error "Unknown argument: $arg"
            exit 2
        }
    }
}

if ([string]::IsNullOrWhiteSpace($inFile) -or
    [string]::IsNullOrWhiteSpace($outFile) -or
    [string]::IsNullOrWhiteSpace($metaFile) -or
    [string]::IsNullOrWhiteSpace($diagFile)) {
    Write-Error "Required args: --in --out --meta --diag"
    exit 2
}

if (-not (Test-Path -LiteralPath $inFile -PathType Leaf)) {
    Write-Error "Input AFP not found: $inFile"
    exit 2
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outFile) | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $metaFile) | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $diagFile) | Out-Null

$pdf = @'
%PDF-1.1
1 0 obj << /Type /Catalog /Pages 2 0 R >>
endobj
2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >>
endobj
3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 300 144] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>
endobj
4 0 obj << /Length 46 >>
stream
BT /F1 12 Tf 36 96 Td (AFP PoC fake engine output) Tj ET
endstream
endobj
5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>
endobj
xref
0 6
0000000000 65535 f
0000000010 00000 n
0000000063 00000 n
0000000122 00000 n
0000000248 00000 n
0000000344 00000 n
trailer << /Size 6 /Root 1 0 R >>
startxref
424
%%EOF
'@

[System.IO.File]::WriteAllText($outFile, $pdf, [System.Text.Encoding]::ASCII)

$inputBytes = (Get-Item -LiteralPath $inFile).Length
$resourceCount = $resources.Count
$source = [System.IO.Path]::GetFileName($inFile)

$meta = @"
{
  "schemaVersion": "1",
  "source": "$source",
  "inputBytes": $inputBytes,
  "textFallback": "$textFallback",
  "resourceCount": $resourceCount
}
"@
[System.IO.File]::WriteAllText($metaFile, $meta, [System.Text.Encoding]::UTF8)

$diag = @'
{
  "schemaVersion": "1",
  "engine": { "name": "fake-cli-engine", "version": "0.1", "build": "dev" },
  "stats": {
    "pageCount": 1,
    "substitutedFonts": 0,
    "missingResources": 0
  },
  "warnings": []
}
'@
[System.IO.File]::WriteAllText($diagFile, $diag, [System.Text.Encoding]::UTF8)

Write-Host "fake-afp-engine.ps1: wrote output.pdf/meta.json/diag.json"
exit 0
