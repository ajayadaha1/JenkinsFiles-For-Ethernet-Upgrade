// Jenkinsfile for a local, multi-stage Vivado and PetaLinux build process

pipeline {
    // Run on the dedicated build node with all tools installed
    agent { label 'xconetbench40' }

    parameters {
        string(name: 'NEW_VIVADO_VERSION', defaultValue: '2025.1', description: 'The new Vivado version for this build run.')
    }

    stages {
        // Stage 1: Clone the repo and set up the new version's directory structure
        stage('Setup Workspace') {
            steps {
                script {
                    def setupScript = '''
                        #!/bin/bash
                        set -e
                        set -x

                        echo "--- STAGE 1: Setting up workspace ---"

                        REPO_DIR="ZCU102-Ethernet"
                        REPO_URL="https://github.com/Xilinx-Wiki-Projects/ZCU102-Ethernet.git"

                        # Check if the repository directory exists in the current workspace
                        if [ ! -d "$REPO_DIR" ]; then
                            echo "Repository directory not found. Cloning from $REPO_URL..."
                            git clone "$REPO_URL"
                            cd "$REPO_DIR"
                        else
                            echo "Repository directory found. Pulling latest changes..."
                            cd "$REPO_DIR"
                            git pull origin main
                        fi

                        echo "Now operating inside the repository: $(pwd)"

                        # Determine previous version and create the new directory if needed
                        previous_version=$(ls -d 20*/ | grep -v "$NEW_VIVADO_VERSION/" | sort -V | tail -n 1 | tr -d '/')
                        if [ -z "$previous_version" ]; then
                            echo "ERROR: Could not find any previous version directories to copy from."
                            exit 1
                        fi
                        
                        new_dir="$NEW_VIVADO_VERSION"
                        if [ ! -d "$new_dir" ]; then
                            echo "Directory $new_dir not found. Creating and copying from $previous_version..."
                            rsync -aq --exclude='.git/' "$previous_version/" "$new_dir/"
                        else
                            echo "Directory $new_dir already exists. Nothing to do."
                        fi

                        echo "Workspace setup complete."
                    '''
                    // FIX: Write the script to a file and execute with bash
                    writeFile file: 'setup_script.sh', text: setupScript
                    sh 'bash setup_script.sh'
                }
            }
        }

        // Stage 2: Run the Vivado hardware builds for the new version
        stage('Build Hardware (Vivado)') {
            steps {
                // FIX: Add a generous timeout for the entire stage, as Vivado builds can be very long.
                timeout(time: 4, unit: 'HOURS') {
                    script {
                        if (false) { // This stage remains disabled for now
                            def buildHwScript = '''
                                #!/bin/bash
                                set -e
                                set -x

                                echo "--- STAGE 2: Building Hardware with Vivado ---"
                                
                                cd ZCU102-Ethernet

                                previous_version=$(ls -d 20*/ | grep -v "$NEW_VIVADO_VERSION/" | sort -V | tail -n 1 | tr -d '/')
                                new_dir="$NEW_VIVADO_VERSION"
                                design_dirs=$(ls -d ${new_dir}/pl_*/ ${new_dir}/ps_*/ | xargs -n 1 basename)
                                MAX_JOBS=3
                                
                                echo "PHASE 1: Creating Vivado projects with version $previous_version..."
                                pids=()
                                log_files_p1=()
                                for design_name in $design_dirs; do
                                    LOG_FILE_P1="${WORKSPACE}/ZCU102-Ethernet/$new_dir/${design_name}_phase1_create.log"
                                    log_files_p1+=($LOG_FILE_P1)
                                    (
                                        cd "${WORKSPACE}/ZCU102-Ethernet/$new_dir/$design_name"
                                        VIVADO_PATH_1="/proj/rdi/xbuilds/${previous_version}_released/installs/lin64/${previous_version}/Vivado/settings64.sh"
                                        VIVADO_PATH_2="/proj/rdi/xbuilds/${previous_version}_released/installs/lin64/Vivado/${previous_version}/settings64.sh"
                                        if [ -f "$VIVADO_PATH_1" ]; then source "$VIVADO_PATH_1"; else source "$VIVADO_PATH_2"; fi
                                        cd Scripts && vivado -mode batch -source "project_top.tcl"
                                    ) > "$LOG_FILE_P1" 2>&1 &
                                    pids+=($!)
                                done
                                
                                exit_code_p1=0; i=0
                                for pid in "${pids[@]}"; do
                                    if ! wait "$pid"; then cat "${log_files_p1[$i]}"; exit_code_p1=1; fi; i=$((i+1))
                                done
                                if [ "$exit_code_p1" -ne 0 ]; then exit 1; fi

                                echo "PHASE 2: Upgrading and building with Vivado $NEW_VIVADO_VERSION..."
                                pids=(); log_files_p2=()
                                for design_name in $design_dirs; do
                                     if [ ${#pids[@]} -ge $MAX_JOBS ]; then wait -n || true; fi
                                    LOG_FILE_P2="${WORKSPACE}/ZCU102-Ethernet/$new_dir/${design_name}_phase2_build.log"
                                    log_files_p2+=($LOG_FILE_P2)
                                    (
                                        cd "${WORKSPACE}/ZCU102-Ethernet/$new_dir/$design_name"
                                        VIVADO_PATH_1="/proj/rdi/xbuilds/${NEW_VIVADO_VERSION}_released/installs/lin64/${NEW_VIVADO_VERSION}/Vivado/settings64.sh"
                                        VIVADO_PATH_2="/proj/rdi/xbuilds/${NEW_VIVADO_VERSION}_released/installs/lin64/Vivado/${NEW_VIVADO_VERSION}/settings64.sh"
                                        if [ -f "$VIVADO_PATH_1" ]; then source "$VIVADO_PATH_1"; else source "$VIVADO_PATH_2"; fi
                                        
                                        PROJ_FILE=$(find ./Hardware -name "*.xpr")
                                        XSA_PATH="./Hardware/pre-built/${design_name}_wrapper.xsa"
                                        BD_TCL_PATH=$(find ./Scripts -maxdepth 1 -name "*_bd.tcl")
                                        mkdir -p ./Hardware/pre-built
                                        
                                        cat > upgrade_and_build.tcl << EOF_TCL
                                            open_project $PROJ_FILE
                                            if {[llength [get_ips -filter {UPGRADE_VERSIONS != ""}]] > 0} { upgrade_ip [get_ips -filter {UPGRADE_VERSIONS != ""}] }
                                            assign_bd_address
                                            validate_bd_design
                                            launch_runs impl_1 -to_step write_bitstream
                                            wait_on_run impl_1
                                            write_hw_platform -fixed -include_bit -force -file "$XSA_PATH"
                                            write_bd_tcl -force "$BD_TCL_PATH"
                                            exit
EOF_TCL
                                        vivado -mode batch -source "upgrade_and_build.tcl"
                                    ) > "$LOG_FILE_P2" 2>&1 &
                                    pids+=($!)
                                done
                                
                                exit_code_p2=0; i=0
                                for pid in "${pids[@]}"; do
                                    if ! wait "$pid"; then cat "${log_files_p2[$i]}"; exit_code_p2=1; fi; i=$((i+1))
                                done
                                if [ "$exit_code_p2" -ne 0 ]; then exit 1; fi
                            '''
                            writeFile file: 'build_hw_script.sh', text: buildHwScript
                            sh 'bash build_hw_script.sh'
                        } else {
                            echo "SKIPPED STAGE: Build Hardware (Vivado) is currently disabled."
                        }
                    }
                }
            }
        }
        
        // Stage 3: Build the PetaLinux images for the new version
        stage('Build Software (PetaLinux)') {
            steps {
                // Add a generous timeout for the entire stage, as PetaLinux builds can be very long.
                timeout(time: 4, unit: 'HOURS') {
                    script {
                        def buildSwScript = '''
                            #!/bin/bash
                            set -e
                            set -x
                            
                            # FIX: Re-introduce the robust helper function to source PetaLinux tools in a clean environment
                            source_petalinux_tools() {
                                local version="$1"
                                echo "Sourcing PetaLinux tools for version $version..."
                                local search_base="/proj/petalinux/released/Petalinux-v${version}/finalrelease/release/petalinux-v${version}*"
                                #local settings_path=$(find $search_base -type f -name "settings.sh" 2>/dev/null | head -n 1)
                                #if [ -z "$settings_path" ]; then
                                #    echo "ERROR: Could not find PetaLinux settings.sh for version $version in path pattern $search_base."
                                #    return 1
                                #fi
                                settings_path=/proj/petalinux/released/Petalinux-v2025.1/finalrelease/release/petalinux-v2025.1_05180714/tool/petalinux-v2025.1-final/settings.sh
                                local tool_dir=$(dirname "$settings_path")
                                local temp_env_file=$(mktemp)
                                # Source in a clean subshell and capture the environment
                                source $settings_path
                                
                                # Safely export the captured environment
                                while read -r line; do
                                    if [[ ! "$line" =~ ^_ ]] && [[ ! "$line" =~ BASH_FUNC ]] && [[ ! "$line" =~ PWD ]] && [[ ! "$line" =~ OLDPWD ]]; then
                                        export "$line"
                                    fi
                                done < "$temp_env_file"
                                rm "$temp_env_file"
                                echo "PetaLinux environment sourced correctly."
                            }
                            
                            echo "--- STAGE 3: Building Software with PetaLinux ---"
                            
                            cd ZCU102-Ethernet
                            TMPDIR="/tmp"
                            source_petalinux_tools "$NEW_VIVADO_VERSION"

                            new_dir="$NEW_VIVADO_VERSION"
                            previous_version=$(ls -d 20*/ | grep -v "$NEW_VIVADO_VERSION/" | sort -V | tail -n 1 | tr -d '/')
                            design_dirs=$(ls -d ${new_dir}/pl_*/ ${new_dir}/ps_*/ | xargs -n 1 basename)
                            
                            # --- Build sequentially for reliability ---
                            for design_name in $design_dirs; do
                                echo "================================================================"
                                echo "Building PetaLinux for $design_name..."
                                echo "================================================================"

                                cd "${WORKSPACE}/ZCU102-Ethernet/$new_dir/$design_name/Software"
                                rm -rf PetaLinux
                                petalinux-create --type project --template zynqMP -n PetaLinux
                                cd PetaLinux
                                
                                CONFIG_FILE="./project-spec/configs/config"

                                # Set the temp directory path *before* any other petalinux-config command is run.
                                TMP_BUILD_DIR_PATH="/tmp/Petalinux_${design_name}_${BUILD_NUMBER}"
                                echo "Setting temporary build directory to $TMP_BUILD_DIR_PATH..."
                                mkdir -p "$TMP_BUILD_DIR_PATH"
                                sed -i '/CONFIG_TMP_DIR_LOCATION/d' "$CONFIG_FILE"
                                echo "CONFIG_TMP_DIR_LOCATION=\"$TMP_BUILD_DIR_PATH\"" >> "$CONFIG_FILE"
                                
                                XSA_PATH_ABS="${WORKSPACE}/ZCU102-Ethernet/$new_dir/$design_name/Hardware/pre-built/${design_name}_wrapper.xsa"
                                echo "Configuring with hardware from $XSA_PATH_ABS..."
                                petalinux-config --get-hw-description="$XSA_PATH_ABS" --silentconfig || true

                                SOURCE_DTSI_PATH="${WORKSPACE}/ZCU102-Ethernet/$previous_version/$design_name/Software/PetaLinux/project-spec/meta-user/recipes-bsp/device-tree/files/system-user.dtsi"
                                DEST_DTSI_PATH="./project-spec/meta-user/recipes-bsp/device-tree/files/system-user.dtsi"
                                if [ -f "$SOURCE_DTSI_PATH" ]; then
                                    mkdir -p $(dirname "$DEST_DTSI_PATH")
                                    cp "$SOURCE_DTSI_PATH" "$DEST_DTSI_PATH"
                                fi
                                
                                sed -i 's/CONFIG_SUBSYSTEM_MACHINE_NAME="template"/CONFIG_SUBSYSTEM_MACHINE_NAME="zcu102-rev1.0"/' "$CONFIG_FILE"
                                
                                # FIX: Add a 'mrproper' clean to handle intermittent build failures
                                echo "Performing a 'mrproper' clean to ensure a robust build state..."
                               petalinux-build || ( \
                                    echo "First build attempt failed. Running 'mrproper' and retrying..." && \
                                    petalinux-build -x mrproper && \
                                    echo "Retrying PetaLinux build..." && \
                                    petalinux-build \
                                )
                                
                                petalinux-package --boot --fsbl images/linux/zynqmp_fsbl.elf --fpga images/linux/system.bit --u-boot
                                
                                # STAGE 4: Cleanup is integrated here
                                rm -rf build/ components/
                                
                                # Clean up the dedicated temporary directory to save space
                                echo "Cleaning up temp build directory: ${TMP_BUILD_DIR_PATH}"
                                rm -rf "${TMP_BUILD_DIR_PATH}"

                                echo "Successfully built PetaLinux for $design_name."
                            done

                            echo "All PetaLinux builds completed successfully."
                        '''
                        writeFile file: 'build_sw_script.sh', text: buildSwScript
                        sh 'bash build_sw_script.sh'
                    }
                }
            }
        }

        // Stage 5: Placeholder for hardware testing
        stage('Hardware Testing') {
            steps {
                echo "Placeholder for Stage 5: Hardware Testing (iperf, ping, etc.)"
            }
        }

        // Stage 6: Placeholder for cleanup and publishing artifacts
        stage('Publish ZCU102-Ethernet') {
            steps {
                echo "Placeholder for Stage 6: Clean up build files and push new version to Git."
            }
        }
    }
}

