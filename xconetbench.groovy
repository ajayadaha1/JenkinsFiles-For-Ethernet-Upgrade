// Jenkinsfile for a local Vivado and PetaLinux build process

pipeline {
    // Run on the dedicated build node with all tools installed
    agent { label 'xconetbench40' }

    parameters {
        string(name: 'NEW_VIVADO_VERSION', defaultValue: '2025.1', description: 'The new Vivado version for this build run.')
    }

    stages {
        stage('Execute Local Build and Upgrade') {
            steps {
                script {
                    def buildScriptContent = '''
                        #!/bin/bash
                        # =================================================================
                        # ALL COMMANDS IN THIS SCRIPT ARE RUNNING ON THE LOCAL JENKINS AGENT
                        # =================================================================
                        set -e # Exit immediately if any command fails
                        set -x # Print every command that is executed for debugging

                        # --- Helper function to source PetaLinux tools in an isolated scope ---
                        source_petalinux_tools() {
                            local version="$1"
                            echo "Sourcing PetaLinux tools for version $version..."
                            
                            # --- TEMPORARY FIX FOR TESTING ---
                            # The following two lines are commented out to use a hardcoded path below.
                            # To restore the dynamic search, uncomment these two lines and comment out the hardcoded path.
                            # local search_base="/proj/petalinux/released/Petalinux-v${version}/finalrelease/release/petalinux-v${version}*"
                            # local settings_path=$(find $search_base -type f -name "settings.sh" 2>/dev/null | head -n 1)

                            # --- HARDCODED PATH FOR TESTING ---
                            # Please replace this path with the exact location of the settings.sh file for your experiment.
                            local settings_path="/proj/petalinux/released/Petalinux-v2025.1/finalrelease/release/petalinux-v2025.1_05180714/tool/petalinux-v2025.1-final/settings.sh"


                            if [ -z "$settings_path" ]; then
                                echo "ERROR: Could not find PetaLinux settings.sh for version $version."
                                return 1
                            fi
                            
                            local tool_dir=$(dirname "$settings_path")
                            
                            local temp_env_file=$(mktemp)
                            # Source the script in a clean subshell and capture its environment.
                            bash -c "cd '$tool_dir' && source ./settings.sh >/dev/null 2>&1 && env" > "$temp_env_file"
                            
                            # Use a safe 'while read' loop to export the variables.
                            while read -r line; do
                                if [[ ! "$line" =~ ^_ ]] && [[ ! "$line" =~ BASH_FUNC ]] && [[ ! "$line" =~ PWD ]] && [[ ! "$line" =~ OLDPWD ]]; then
                                    export "$line"
                                fi
                            done < "$temp_env_file"
                            
                            rm "$temp_env_file"
                            
                            echo "PetaLinux environment sourced correctly."
                        }

                        # --- 0. Read parameters passed from Jenkins ---
                        echo "Successfully received new version parameter: $NEW_VIVADO_VERSION"

                        # --- 1. Navigate to the dedicated workspace and ensure repo exists ---
                        # Use the new, faster local NFS path
                        WORK_DIR="/group/bcapps/ajayad/Jenkins/"
                        REPO_DIR="ZCU102-Ethernet"
                        REPO_URL="https://github.com/Xilinx-Wiki-Projects/ZCU102-Ethernet.git"
                        
                        echo "Ensuring workspace exists at $WORK_DIR"
                        mkdir -p "$WORK_DIR"
                        cd "$WORK_DIR"

                        if [ ! -d "$REPO_DIR" ]; then
                            echo "Repository not found in workspace. Cloning from $REPO_URL..."
                            git clone "$REPO_URL"
                        else
                            echo "Repository found. Pulling latest changes..."
                            cd "$REPO_DIR"
                            git pull
                        fi
                        
                        # Move into the repository for the rest of the script
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
                            rsync -aq --exclude='.git/' "$previous_version/" "$new_dir/"
                        fi
                        
                        # Use a robust command to find design directories
                        design_dirs=$(ls -d ${new_dir}/pl_*/ ${new_dir}/ps_*/ | xargs -n 1 basename)

                        # =================================================================
                        # --- SKIPPING PHASE 1 & 2 FOR PETALINUX TESTING ---
                        # To re-enable, remove the surrounding 'if false; then' and 'fi' markers.
                        # =================================================================
                        if false; then
                            # --- PHASE 1: Create all projects in parallel using OLD tools ---
                            echo "PHASE 1: Starting project creation with Vivado $previous_version"
                            MAX_JOBS=3
                            pids=()
                            log_files_p1=()
                            
                            for design_name in $design_dirs; do
                                LOG_FILE_P1="$WORK_DIR/$REPO_DIR/$new_dir/${design_name}_phase1_create.log"
                                log_files_p1+=($LOG_FILE_P1)
                                (
                                    echo "Creating project for $design_name..."
                                    cd "$WORK_DIR/$REPO_DIR/$new_dir/$design_name"
                                    
                                    VIVADO_PATH_1="/proj/rdi/xbuilds/${previous_version}_released/installs/lin64/${previous_version}/Vivado/settings64.sh"
                                    VIVADO_PATH_2="/proj/rdi/xbuilds/${previous_version}_released/installs/lin64/Vivado/${previous_version}/settings64.sh"

                                    if [ -f "$VIVADO_PATH_1" ]; then source "$VIVADO_PATH_1"; elif [ -f "$VIVADO_PATH_2" ]; then source "$VIVADO_PATH_2"; else echo "ERROR: Old Vivado path not found" && exit 1; fi
                                    
                                    cd Scripts
                                    vivado -mode batch -source "project_top.tcl"
                                    
                                    if [ -z "$(find ../Hardware -maxdepth 1 -type d -name '*_hw')" ]; then
                                        echo "ERROR: Project creation failed for $design_name." && exit 1
                                    fi
                                ) > "$LOG_FILE_P1" 2>&1 &
                                pids+=($!)
                            done

                            echo "Waiting for all project creation jobs to complete..."
                            exit_code_p1=0; i=0
                            for pid in "${pids[@]}"; do
                                if ! wait "$pid"; then
                                    echo "ERROR: A project creation job failed (PID $pid). See log: ${log_files_p1[$i]}"
                                    cat "${log_files_p1[$i]}"
                                    exit_code_p1=1
                                fi; i=$((i+1))
                            done
                            if [ "$exit_code_p1" -ne 0 ]; then exit 1; fi
                            echo "PHASE 1 Complete: All projects created successfully."

                            # --- PHASE 2: Upgrade and Build all projects in parallel using NEW tools ---
                            echo "PHASE 2: Starting project upgrade and build with Vivado $NEW_VIVADO_VERSION"
                            pids=(); log_files_p2=()

                            for design_name in $design_dirs; do
                                 if [ ${#pids[@]} -ge $MAX_JOBS ]; then wait -n || true; fi
                                LOG_FILE_P2="$WORK_DIR/$REPO_DIR/$new_dir/${design_name}_phase2_build.log"
                                log_files_p2+=($LOG_FILE_P2)
                                (
                                    echo "Upgrading and building $design_name..."
                                    cd "$WORK_DIR/$REPO_DIR/$new_dir/$design_name"

                                    VIVADO_PATH_1="/proj/rdi/xbuilds/${NEW_VIVADO_VERSION}_released/installs/lin64/${NEW_VIVADO_VERSION}/Vivado/settings64.sh"
                                    VIVADO_PATH_2="/proj/rdi/xbuilds/${NEW_VIVADO_VERSION}_released/installs/lin64/Vivado/${NEW_VIVADO_VERSION}/settings64.sh"
                                    if [ -f "$VIVADO_PATH_1" ]; then source "$VIVADO_PATH_1"; elif [ -f "$VIVADO_PATH_2" ]; then source "$VIVADO_PATH_2"; else echo "ERROR: New Vivado path not found" && exit 1; fi

                                    PROJ_FILE=$(find ./Hardware -name "*.xpr")
                                    XSA_PATH="./Hardware/pre-built/${design_name}_wrapper.xsa"
                                    BD_TCL_PATH=$(find ./Scripts -maxdepth 1 -name "*_bd.tcl")
                                    if [ -z "$BD_TCL_PATH" ]; then echo "ERROR: Could not find '*_bd.tcl' file for $design_name." && exit 1; fi
                                    mkdir -p ./Hardware/pre-built
                                    
                                    cat > upgrade_and_build.tcl << EOF_TCL
open_project $PROJ_FILE
if {[llength [get_ips -filter {UPGRADE_VERSIONS != ""}]] > 0} {
    upgrade_ip [get_ips -filter {UPGRADE_VERSIONS != ""}]
}
assign_bd_address
validate_bd_design
launch_runs impl_1 -to_step write_bitstream
wait_on_run impl_1
write_hw_platform -fixed -include_bit -force -file "$XSA_PATH"
write_bd_tcl -force "$BD_TCL_PATH"
exit
EOF_TCL
                                    vivado -mode batch -source "upgrade_and_build.tcl"
                                    if [ ! -f "$XSA_PATH" ]; then echo "ERROR: XSA file not found for $design_name." && exit 1; fi
                                ) > "$LOG_FILE_P2" 2>&1 &
                                pids+=($!)
                            done

                            echo "Waiting for all upgrade and build jobs to complete..."
                            exit_code_p2=0; i=0
                            for pid in "${pids[@]}"; do
                                if ! wait "$pid"; then
                                    echo "ERROR: Build job for PID $pid failed. See log: ${log_files_p2[$i]}"
                                    cat "${log_files_p2[$i]}"
                                    exit_code_p2=1 
                                fi; i=$((i+1))
                            done
                            if [ "$exit_code_p2" -ne 0 ]; then exit 1; fi
                            echo "PHASE 2 Complete: All Vivado builds finished successfully."
                        fi
                        echo "SKIPPED PHASE 1 & 2: Assuming Vivado projects exist and XSAs are generated."

                    # --- PHASE 3: Build PetaLinux for all designs sequentially ---
                    echo "PHASE 3: Starting PetaLinux builds with version $NEW_VIVADO_VERSION"
                    
                    # Call our helper function to set up the environment cleanly.
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
                        # FIX: Add '|| true' to prevent the script from exiting on a benign bitbake server timeout.
                        petalinux-config --get-hw-description="$XSA_PATH_ABS" --silentconfig || true

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
                
                // FIX: Write the script to a file and execute it with bash to ensure the correct shell is used.
                writeFile file: 'build_script.sh', text: buildScriptContent
                sh 'bash build_script.sh'
                }
            }
        }
    }
}

