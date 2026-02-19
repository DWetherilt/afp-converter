import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / 'build.gradle'


class EnvironmentReadyChainTests(unittest.TestCase):
    def test_environment_ready_chain_tokens_present(self):
        text = BUILD.read_text(encoding='utf-8')
        required = [
            "tasks.register('pmEnvironmentReady')",
            "dependsOn('pmEnvironmentKnowledgeEvidence')",
            "dependsOn('pmConsoleVisibilityKnowledgeEvidence')",
            "dependsOn('pmConsoleSessionIdentityKnowledgeEvidence')",
            "dependsOn('pmConsoleCleanupStaleSessions')",
            "tasks.register('qualityGate')",
            "dependsOn('pmEnvironmentReady')",
        ]
        for token in required:
            self.assertIn(token, text, token)


if __name__ == '__main__':
    unittest.main()
