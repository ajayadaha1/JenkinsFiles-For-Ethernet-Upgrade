#!/bin/bash
# =================================================================
# Standalone Roast Test Runner
#
# This script creates a complete, self-contained test environment
# by generating a conf.py and a test file, and then executes the test.
# =================================================================
set -e # Exit immediately if any command fails
set -x # Print every command that is executed for debugging

echo "--- Creating test suite files ---"
mkdir -p tests

# --- 1. Create the Python configuration file (conf.py) ---
# This file contains all the necessary settings for the test run,
# based on the user's working example.
cat > tests/conf.py << 'EOF'
# Test Environment Configuration
import os

# --- Dynamic Version Configuration ---
version = os.environ.get("NEW_VIVADO_VERSION", "2025.1")
build = f"{version}_daily_latest"
plnx_build = f"petalinux-v{version}_released"

# --- Board Farm Integration Settings ---
board_interface = "systest"
board_type = "zcu102"
systest_base_host = "xsjbf1"
boards = ["zcu102"]
systest_init_cmds = ['message "Jenkins ZCU102 Ethernet Automation"']

# --- Serial and Login Settings ---
com = "/dev/ttyUSB0"
baudrate = "115200"
target_user = "petalinux"
target_password = "root"
autologin = True

# --- Tool and Project Path Configurations ---
vitisPath = f"/proj/xbuilds/{build}/installs/lin64/{version}/Vitis/"
PLNX_TOOL = (
    f"/proj/petalinux/released/Petalinux-v{version}/finalrelease/release/"
    f"petalinux-v{version}_05180714/tool/petalinux-v{version}-final/settings.sh"
)
BSP_PATH = f"/proj/petalinux/released/Petalinux-v{version}/finalrelease/release/petalinux-v{version}_05180714/bsp/release"

# --- Image and Project Definitions ---
design_name = "pl_eth_10g" # Placeholder, can be overridden
workspace = "/public/cases/ajayad/Ethernet_Design_Hub"
IMAGE_DIR = os.path.join(workspace, "ZCU102-Ethernet", "2024.2", design_name, "Software", "PetaLinux", "images", "linux")
imagesDir = IMAGE_DIR
plnx_proj_path = os.getcwd()
PLNX_TMP_PATH = "/tmp/zcu102-jenkins-upgrade"
plnx_proj = "./" # os.getcwd() # f"xilinx-zcu102-v{version}-final"

# --- Boot Configuration ---
boottype = "jtag_boot"
platform = "zynqmp"
load_interface = "petalinux"
EOF

# --- 2. Create the pytest conftest.py to apply workarounds ---
cat > tests/conftest.py << 'EOF'
import pytest

# This hook is a workaround for a bug in this version of the roast library.
# The library's internal code expects 'pytest' to have an 'lfs' attribute.
def pytest_configure(config):
    print("Applying workaround for pytest.lfs attribute...")
    pytest.lfs = None
EOF


# --- 3. Create the main pytest test file ---
cat > tests/test_deployment.py << 'EOF'
import pytest
import os
import socket
import logging
import time
from roast.component.petalinux import petalinux_boot
from roast.component.board.boot import _petalinux_login, linuxcons, uboot_login
from roast.component.petalinux import *

# Import our custom configuration
import conf

# Configure basic logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
log = logging.getLogger(__name__)

# This test class uses the fixtures provided by pytest-roast
class TestDeployment:

    def test_jtag_boot_and_verify(self, systest_board_session, create_configuration):
        """
        Acquires a board, deploys images via JTAG,
        and verifies that it boots to a PetaLinux prompt.
        """
        
        # --- 1. Get the board and config from the roast fixtures ---
        board = systest_board_session
        config = create_configuration(base_params=['conf.py'])
        # --- 2. Set up absolute image paths ---
        image_dir = conf.IMAGE_DIR
        boot_bin_path = os.path.abspath(os.path.join(image_dir, "BOOT.BIN"))
        image_ub_path = os.path.abspath(os.path.join(image_dir, "image.ub"))
        bitstream_path = os.path.abspath(os.path.join(image_dir, "system.bit"))
        # Add deployment-specific info to the board's configuration
        config['image_deploy'] = f"boot_bin={boot_bin_path},linux_image={image_ub_path}"
        

        board.config = config

        

        if not os.path.exists(boot_bin_path):
            pytest.fail(f"BOOT.BIN not found at: {boot_bin_path}")
        if not os.path.exists(image_ub_path):
            pytest.fail(f"image.ub not found at: {image_ub_path}")
        if not os.path.exists(bitstream_path):
            pytest.fail(f"system.bit not found at: {bitstream_path}")

        log.info("Image files verified successfully.")
        
        # --- 3. Start the board session and boot ---
        board.invoke_xsdb = False
        board.invoke_hwserver = True
        board.reboot = True
        board.start() # This executes 'systest' on the base_host
        log.info(f"Board acquired via SBC '{board.config['systest_base_host']}'")
        
      
        log.info("Initiating JTAG boot...")
        # Use 'jtag_boot' and pass the correct hwserver host and bitfile
        petalinux_boot(board.config, boottype="kernel", hwserver=board.systest.systest_host, bitfile=bitstream_path)
        log.info("JTAG boot command sent.")
        #uboot_login(board.serial)
        time.sleep(5)
        log.info("Waiting for PetaLinux login prompt...")
            
        #_petalinux_login(board)
        board.serial.prompt = None
        linux_login_cons(board.serial)
        log.info("SUCCESS: Board has booted to PetaLinux successfully!")
EOF


# --- 4. Activate Pre-existing Python Virtual Environment ---
echo "Activating pre-existing Python virtual environment..."
if [ ! -f ".venv/bin/activate" ]; then
    echo "ERROR: Virtual environment not found."
    echo "Please ensure the '.venv' directory with all dependencies exists."
    exit 1
fi
source .venv/bin/activate


# --- 5. Run the Test ---
echo "================================================================"
echo "Running Pytest..."
echo "================================================================"

# Pytest will automatically discover and use the tests/conf.py and tests/conftest.py files
pytest -s --verbose "tests/test_deployment.py" --log-file="tests/test_run.log"

# --- Teardown ---
deactivate
echo "Test completed."

