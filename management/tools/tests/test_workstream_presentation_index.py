import json
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "workstream_presentation_index.py"


class WorkstreamPresentationIndexTest(unittest.TestCase):
    def test_generates_active_aliases_only(self):
        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            csv_path = base / "project-plan-progress.csv"
            out_path = base / "workstream-presentation-index.json"
            csv_path.write_text(
                textwrap.dedent(
                    """\
                    workstream_uid,workstream_label,workstream_priority_rank,workstream,task,priority,status,percent_complete,last_updated,notes
                    WS-2026-001,Renderer Continuation,11,Renderer Continuation,Task A,High,In Progress,50,2026-02-23,
                    WS-2026-001,Renderer Continuation,11,Renderer Continuation,Task B,High,Not Started,0,2026-02-23,
                    WS-A,Workstream A,1,Workstream A,Historical,High,Complete,100,2026-02-18,[github:committed]
                    WS-2026-002,Policy Followup,12,Policy Followup,Task C,Medium,Not Started,0,2026-02-23,
                    """
                ),
                encoding="utf-8",
            )

            subprocess.run(
                [
                    "python3",
                    str(SCRIPT),
                    "--csv",
                    str(csv_path),
                    "--output",
                    str(out_path),
                ],
                check=True,
            )

            payload = json.loads(out_path.read_text(encoding="utf-8"))
            self.assertEqual(payload["activeWorkstreamCount"], 2)
            self.assertEqual(payload["activeWorkstreams"][0]["presentationWorkstream"], "Workstream A")
            self.assertEqual(payload["activeWorkstreams"][0]["internalWorkstreamUid"], "WS-2026-001")
            self.assertEqual(payload["activeWorkstreams"][1]["presentationWorkstream"], "Workstream B")
            self.assertEqual(payload["activeWorkstreams"][1]["internalWorkstreamUid"], "WS-2026-002")


if __name__ == "__main__":
    unittest.main()
