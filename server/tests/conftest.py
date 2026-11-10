import sys
from pathlib import Path

# The plugin directory is not a valid package name, so tests import its modules directly.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "hermes-app"))
