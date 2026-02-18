#!/usr/bin/env bash
set -euo pipefail

in_file=""
out_file=""
meta_file=""
diag_file=""
text_fallback="SUBSTITUTE"
resources=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --in)
      in_file="${2:-}"
      shift 2
      ;;
    --out)
      out_file="${2:-}"
      shift 2
      ;;
    --meta)
      meta_file="${2:-}"
      shift 2
      ;;
    --diag)
      diag_file="${2:-}"
      shift 2
      ;;
    --resources)
      resources+=("${2:-}")
      shift 2
      ;;
    --textFallback)
      text_fallback="${2:-SUBSTITUTE}"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ -z "$in_file" || -z "$out_file" || -z "$meta_file" || -z "$diag_file" ]]; then
  echo "Required args: --in --out --meta --diag" >&2
  exit 2
fi

if [[ ! -f "$in_file" ]]; then
  echo "Input AFP not found: $in_file" >&2
  exit 2
fi

mkdir -p "$(dirname "$out_file")" "$(dirname "$meta_file")" "$(dirname "$diag_file")"

# Tiny text-based PDF with visible text object.
cat > "$out_file" <<'PDF'
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
PDF

resource_count="${#resources[@]}"
input_bytes="$(wc -c < "$in_file" | tr -d ' ')"

cat > "$meta_file" <<JSON
{
  "schemaVersion": "1",
  "source": "$(basename "$in_file")",
  "inputBytes": $input_bytes,
  "textFallback": "$text_fallback",
  "resourceCount": $resource_count
}
JSON

cat > "$diag_file" <<JSON
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
JSON

echo "fake-afp-engine: wrote output.pdf/meta.json/diag.json"
