"""Allow running as ``python -m emulator_harness``."""
import sys

from emulator_harness.main import main

sys.exit(main())