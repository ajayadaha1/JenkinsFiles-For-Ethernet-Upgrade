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

# This file contains all the necessary settings for the test run.
cat > tests/conf.py << 'EOF'
import pytest
# Test Environment Configuration
# This is needed to dynamically construct the vitisPath
import os
version = os.environ.get("NEW_VIVADO_VERSION", "2025.1")
build = f"{version}_daily_latest"

# Board farm integration settings
board_interface = "systest"
board_type = "zcu102"
systest_base_host = "xsjbf1"
boards = ["zcu102"]
systest_init_cmds = ['message "Jenkins ZCU102 Ethernet Automation"']

# Serial port settings
com = "/dev/ttyUSB0"
baudrate = "115200"

# Target login credentials
target_user = "petalinux"
target_password = "root"
autologin = True

# TFTP Boot Configuration
ip = "10.10.70.101" 
image_address = "200000"
rootfs_address = "4000000"
dtb_address = "100000"

#Add the mandatory vitisPath required by the boot components.
vitisPath = f"/proj/xbuilds/{build}/installs/lin64/{version}/Vitis/"


# --- 2. Verify image paths ---
design_name = "pl_eth_10g" 
workspace = "/public/cases/ajayad/Ethernet_Design_Hub"
IMAGE_DIR = os.path.join(workspace, "ZCU102-Ethernet", "2024.2", design_name, "Software", "PetaLinux", "images", "linux")       
#IMAGE_DIR = "./images/linux/"
image_path = os.path.abspath(IMAGE_DIR)
if not os.path.exists(os.path.join(image_path, "Image")):
    pytest.fail(f"Kernel 'Image' not found in: {image_path}")
if not os.path.exists(os.path.join(image_path, "rootfs.cpio.gz.u-boot")):
    pytest.fail(f"Rootfs 'rootfs.cpio.gz.u-boot' not found in: {image_path}")
if not os.path.exists(os.path.join(image_path, "system.dtb")):
    pytest.fail(f"Device tree 'system.dtb' not found in: {image_path}")

#log.info("Image files verified successfully.")
component_deploy_dir = image_path
boot_images = "kernel"
boottype = "jtag_boot"
prebuilt_boot_type = "jtag_boot"
plnx_proj_path = "./"
plnx_build = "petalinux-v{version}_released"
imagesDir = IMAGE_DIR
image_deploy = IMAGE_DIR

PLNX_TMP_PATH = "/tmp/zcu102-jenkins-upgrade"
BSP_PATH = "/proj/petalinux/released/Petalinux-v{version}/finalrelease/release/petalinux-v{version}_05180714/bsp/release"

PLNX_TOOL = (
    "/proj/petalinux/released/Petalinux-v{version}/finalrelease/release/petalinux-v{version}_05180714/tool/petalinux-v{version}-final/settings.sh"
)


PLNX_GOLDEN_BSP = ""

load_interface = "petalinux"
platform = "zynqmp"

# Qemu configurations
plnx_wsDir = "./"
load_interface == "petalinux"
QEMU_TMP = "/tmp/qemu_softip_linux"
plnx_proj_dir = "./"
plnx_proj = "xilinx-zcu102-v{version}-final",
bootstage = "kernel"
qemu_args = (
    "--rootfs images/linux/rootfs.cpio.gz.u-boot --tftp {plnx_proj_dir}/images/linux/"
)

from box import Box

plnx = Box(default_box=True, box_intact_types=[list, tuple, dict])
plnx.component.kernel.url = ""
plnx.component.kernel.branch = ""
plnx.component.kernel.srcrev = ""
plnx.component.kernel.checksum = ""
plnx.component.kernel.externalsrc = ""
plnx.component.uboot.url = ""
plnx.component.uboot.branch = ""
plnx.component.uboot.srcrev = ""
plnx.component.uboot.checksum = ""
plnx.component.uboot.externalsrc = ""
plnx.component.atf.url = ""
plnx.component.atf.branch = ""
plnx.component.atf.srcrev = ""
plnx.component.atf.checksum = ""
plnx.component.atf.externalsrc = ""


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
# FIX: Import the linuxcons function
from roast.component.board.boot import uboot_login, _petalinux_login, linuxcons, jtag_boot
from roast.component.basebuild import Basebuild
from roast.component.board.board import Board

# Import our custom configuration
import conf

# Configure basic logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
log = logging.getLogger(__name__)



# This test class uses the fixtures provided by pytest-roast
class TestDeployment:


    def test_boot_and_verify(self, systest_board_session, create_configuration):
        """
        Acquires a board, boots to U-Boot, and then uses TFTP
        to download and boot the PetaLinux kernel and rootfs.
        """
        
        # --- 1. Get the board and config from the roast fixtures ---
        config = create_configuration(base_params=['conf.py'])
        design_name = "pl_eth_10g" 
        workspace = "/public/cases/ajayad/Ethernet_Design_Hub"
        version = "2024.2"
        image_dir = os.path.join(workspace, "ZCU102-Ethernet", version, design_name, "Software", "PetaLinux", "images", "linux")
        
        boot_bin_path = os.path.abspath(os.path.join(image_dir, "BOOT.BIN"))
        image_ub_path = os.path.abspath(os.path.join(image_dir, "image.ub"))
        bitstream_path = os.path.abspath(os.path.join(image_dir, "system.bit"))
        if not os.path.exists(boot_bin_path):
            pytest.fail(f"BOOT.BIN not found at: {boot_bin_path}")
        if not os.path.exists(image_ub_path):
            pytest.fail(f"image.ub not found at: {image_ub_path}")

        log.info("Image files verified successfully.")
        
        # --- 3. Start the boot process ---
        
        config['image_deploy'] = f"boot_bin={boot_bin_path},linux_image={image_ub_path}, bitfile={bitstream_path}"
        
        board = linuxcons(config, systest_board_session)


        
        #petalinux_boot(board.config, boottype="jtag_boot", hwserver=board.config['systest_base_host'], bitfile=bitstream_path)
        log.info("JTAG boot command sent.")
        
        # --- 4. Verify Linux boot ---
        board.serial.prompt = None # Reset prompt for Linux login
        log.info("Waiting for PetaLinux login prompt...")
        _petalinux_login(board)
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

