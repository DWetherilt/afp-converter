# Apply Checklist

1. Restore protected `sampleOutput` baseline files from known-good commit/reference.
2. Update sample generation tasks to write under `preview/generated-sample-output`.
3. Add/refresh `sample-output-reference-manifest.json` with expected hashes.
4. Add `enforceSampleOutputReferenceIntegrity` to quality gates.
5. Run quality gates and PM refresh/report cycle.
6. Commit and push.
