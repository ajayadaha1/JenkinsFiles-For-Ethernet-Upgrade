// Jenkinsfile to orchestrate a remote Vivado and PetaLinux build process via SSH

pipeline {
    agent any

    parameters {
        string(name: 'NEW_VIVADO_VERSION', defaultValue: '2025.1', description: 'The new Vivado version for this build run.')
    }

    stages {
        stage('Execute Remote Build and Upgrade') {
            steps {
                script {
                    echo "--- Preparing remote build script ---"
                    
                    def newVersion = params.NEW_VIVADO_VERSION

                    // Define the entire remote script as a simple, multi-line string.
                    def remoteScriptContent = '''
                        #!/bin/bash
                        # =================================================================
                        # ALL COMMANDS IN THIS SCRIPT ARE RUNNING ON THE REMOTE MACHINE
                        # =================================================================
                        set -e # Exit immediately if any command fails
                        set -x # Print every command that is executed for debugging

                        # --- Helper function to source PetaLinux tools in an isolated scope ---
                        source_petalinux_tools() {
                            local version="$1"
                            echo "Sourcing PetaLinux tools for version $version..."
                            
                            local settings_path=$(timeout 60s find /proj/petalinux/${version}* -type f -name "settings.sh" | head -n 1)
                            if [ -z "$settings_path" ]; then
                                echo "ERROR: Could not find PetaLinux settings.sh for version $version."
                                return 1
                            fi
                            
                            local tool_dir=$(dirname "$settings_path")
                            
                            local temp_env_file=$(mktemp)
                            bash -c "cd '$tool_dir' && source ./settings.sh >/dev/null 2>&1 && env" > "$temp_env_file"
                            
                            while read -r line; do
                                if [[ ! "$line" =~ ^_ ]] && [[ ! "$line" =~ BASH_FUNC ]] && [[ ! "$line" =~ PWD ]] && [[ ! "$line" =~ OLDPWD ]]; then
                                    export "$line"
                                fi
                            done < "$temp_env_file"
                            
                            rm "$temp_env_file"
                            
                            echo "PetaLinux environment sourced correctly."
                        }


                        # --- 0. Read parameters passed from Jenkins ---
                        export NEW_VIVADO_VERSION="$1"
                        echo "Successfully received new version parameter: $NEW_VIVADO_VERSION"

                        # --- 1. Navigate to the workspace and ensure repo exists ---
                        WORK_DIR="/public/cases/ajayad/Ethernet_Design_Hub/zcu102-ethernet-automatic-upgrade"
                        REPO_DIR="ZCU102-Ethernet"
                        REPO_URL="https://github.com/Xilinx-Wiki-Projects/ZCU102-Ethernet.git"
                        cd "$WORK_DIR"
                        if [ ! -d "$REPO_DIR" ]; then
                            git clone "$REPO_URL"
                        else
                            cd "$REPO_DIR"
                            git pull
                        fi
                        cd "$WORK_DIR/$REPO_DIR"
                        echo "Working inside: $(pwd)"

                        # --- 2. Determine previous version and other setup ---
                        previous_version=$(ls -d 20*/ | grep -v "$NEW_VIVADO_VERSION/" | sort -V | tail -n 1 | tr -d '/')
                        if [ -z "$previous_version" ]; then
                            echo "ERROR: Could not find any previous version directories to copy from."
                            exit 1
                        fi
                        
                        new_dir="$NEW_VIVADO_VERSION"
                        if [ ! -d "$new_dir" ]; then
                            echo "ERROR: Directory $new_dir does not exist. Cannot skip to PetaLinux build."
                            exit 1
                        fi
                        
                        design_dirs=$(find "$new_dir" -maxdepth 1 -type d \\( -name 'pl_*' -o -name 'ps_*' \\) -printf '%f ')

                        # =================================================================
                        # --- SKIPPING PHASE 1 & 2 FOR PETALINUX TESTING ---
                        echo "SKIPPED PHASE 1 & 2: Assuming Vivado projects exist and XSAs are generated."
                        # =================================================================

                        # --- 3. KERNEL PREP: Clone the kernel source locally once ---
                        KERNEL_TAG="xilinx-v${NEW_VIVADO_VERSION}"
                        KERNEL_REPO_URL="https://github.com/Xilinx/linux-xlnx.git"
                        LOCAL_KERNEL_PATH="$WORK_DIR/$REPO_DIR/$new_dir/linux-xlnx-src"

                        echo "Preparing local kernel source at ${LOCAL_KERNEL_PATH}"
                        if [ ! -d "$LOCAL_KERNEL_PATH" ]; then
                            echo "Local kernel source not found. Cloning tag ${KERNEL_TAG}..."
                            # Perform a shallow clone of only the required tag to save time and space
                            git clone --depth 1 --branch "${KERNEL_TAG}" "${KERNEL_REPO_URL}" "${LOCAL_KERNEL_PATH}"
                        else
                            echo "Local kernel source already exists. Skipping clone."
                        fi

                        # --- 4. PHASE 3: Build PetaLinux for all designs sequentially ---
                        echo "PHASE 3: Starting PetaLinux builds with version $NEW_VIVADO_VERSION"
                        
                        source_petalinux_tools "$NEW_VIVADO_VERSION"

                        for design_name in $design_dirs; do
                            echo "================================================================"
                            echo "Building PetaLinux for $design_name..."
                            echo "================================================================"
                            cd "$WORK_DIR/$REPO_DIR/$new_dir/$design_name/Software"

                            echo "Cleaning up old project directory..."
                            rm -rf PetaLinux
                            
                            echo "Creating new PetaLinux project..."
                            petalinux-create --type project --template zynqMP -n PetaLinux
                            cd PetaLinux
                            
                            XSA_PATH_ABS="$WORK_DIR/$REPO_DIR/$new_dir/$design_name/Hardware/pre-built/${design_name}_wrapper.xsa"
                            echo "Configuring with hardware from $XSA_PATH_ABS..."
                            petalinux-config --get-hw-description="$XSA_PATH_ABS" --silentconfig

                            echo "Copying system-user.dtsi from previous version..."
                            SOURCE_DTSI_PATH="$WORK_DIR/$REPO_DIR/$previous_version/$design_name/Software/PetaLinux/project-spec/meta-user/recipes-bsp/device-tree/files/system-user.dtsi"
                            DEST_DTSI_PATH="./project-spec/meta-user/recipes-bsp/device-tree/files/system-user.dtsi"
                            
                            if [ -f "$SOURCE_DTSI_PATH" ]; then
                                mkdir -p $(dirname "$DEST_DTSI_PATH")
                                cp "$SOURCE_DTSI_PATH" "$DEST_DTSI_PATH"
                            else
                                echo "WARNING: No system-user.dtsi found in previous version at $SOURCE_DTSI_PATH."
                            fi

                            CONFIG_FILE="./project-spec/configs/config"

                            echo "Setting machine name to zcu102-rev1.0..."
                            sed -i 's/CONFIG_SUBSYSTEM_MACHINE_NAME="template"/CONFIG_SUBSYSTEM_MACHINE_NAME="zcu102-rev1.0"/' "$CONFIG_FILE"

                            # echo "Changing tmp directory from /tmp to /var/tmp..."
                            # sed -i 's|CONFIG_TMP_DIR_LOCATION="/tmp/|CONFIG_TMP_DIR_LOCATION="/var/tmp/|' "$CONFIG_FILE"

                            echo "Overriding kernel source to use local repository at ${LOCAL_KERNEL_PATH}..."
                            sed -i 's/CONFIG_SUBSYSTEM_COMPONENT_LINUX__KERNEL_NAME_LINUX__XLNX=y/# CONFIG_SUBSYSTEM_COMPONENT_LINUX__KERNEL_NAME_LINUX__XLNX is not set/' "$CONFIG_FILE"
                            sed -i 's/# CONFIG_SUBSYSTEM_COMPONENT_LINUX__KERNEL_NAME_EXT__LOCAL__SRC is not set/CONFIG_SUBSYSTEM_COMPONENT_LINUX__KERNEL_NAME_EXT__LOCAL__SRC=y/' "$CONFIG_FILE"
                            echo "CONFIG_SUBSYSTEM_COMPONENT_LINUX__KERNEL_NAME_EXT_LOCAL_SRC_PATH=\"${LOCAL_KERNEL_PATH}\"" >> "$CONFIG_FILE"

                            # FIX: Force the build system to re-read and apply our manual config changes to the whole project
                            echo "Applying all manual configuration changes..."
                            petalinux-config --silentconfig

                            echo "Building PetaLinux project..."
                            petalinux-build
                            
                            echo "Packaging boot image..."
                            petalinux-package --boot --fsbl images/linux/zynqmp_fsbl.elf --fpga images/linux/system.bit --u-boot
                            
                            if [ ! -f "images/linux/BOOT.BIN" ]; then
                                echo "ERROR: PetaLinux build failed for $design_name. BOOT.BIN not found." && exit 1
                            fi

                            echo "Build successful. Cleaning up temporary build directories..."
                            rm -rf build/ components/
                            
                            echo "Successfully built PetaLinux for $design_name."
                        done

                        echo "All PetaLinux builds completed successfully."
                        echo "All phases completed successfully."
                    '''

                    // Jenkins workflow steps remain the same
                    writeFile file: 'remote_build_script.sh', text: remoteScriptContent
                    sshagent(credentials: ['remote-vivado-machine-key']) {
                        def userAndHost = 'ajayad@172.19.74.155'
                        def remoteScriptPath = "/tmp/remote_build_script_${BUILD_NUMBER}.sh"
                        
                        sh "scp -o StrictHostKeyChecking=no remote_build_script.sh ${userAndHost}:${remoteScriptPath}"
                        sh "ssh -o StrictHostKeyChecking=no ${userAndHost} 'chmod +x ${remoteScriptPath}'"
                        sh "ssh -o StrictHostKeyChecking=no ${userAndHost} '${remoteScriptPath} ${newVersion}'"
                        sh "ssh -o StrictHostKeyChecking=no ${userAndHost} 'rm ${remoteScriptPath}'"
                    }
                }
            }
        }
    }
}

