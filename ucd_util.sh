#!/usr/bin/env bash

#CAN_APPLY_FILES="apply_files.txt"
#SNAPSHOT_TEMPLATE="snapshot.php"
#UCD_APPLICATION_NAME="ApplyHotfixForSC.DEV"
#UCD_GENERATE_PATCH_FILE_COMPONENT="Generate-patch-file.DEV"
#PR_NUMBER=31732

function create_snapshot
{
    [[ -z "${PR_NUMBER}" ]] && echo "Error: PR is empty, please set PR_NUMBER environment variable." && exit 1

    if [[ ! -d "${PR_NUMBER}" ]]; then
        echo "Error: Directory ${PR_NUMBER} not found!"
        exit 1
    fi
    cd "${PR_NUMBER}" || exit 1

    if [[ ! -f "${CAN_APPLY_FILES}" ]]; then
        echo "Error: File ${CAN_APPLY_FILES} not exists"
        exit 1
    fi

    cat <<SNAPSHOT > "${SNAPSHOT_TEMPLATE}"
<?php
\$config = array();
\$config['snapshot']['name'] = "${PR_NUMBER}_PR_HOTFIX";
\$config['snapshot']['description'] = "SalesConnect snapshot for build Application ApplyHotfixForSC";
\$config['application'] = "${UCD_APPLICATION_NAME}";
SNAPSHOT

    while IFS='' read -r line || [[ -n "$line" ]]; do
        include_files=''
        version_name=$(echo "${line}" | sed 's/\//-/g')
        for i_file in ${line}*; do
            include_files="'${i_file}', ${include_files}"
        done
        echo "\$config['components']['$UCD_GENERATE_PATCH_FILE_COMPONENT'][] = \"${version_name}\";" >> "${SNAPSHOT_TEMPLATE}"
        echo "\$config['artifacts']['$UCD_GENERATE_PATCH_FILE_COMPONENT']['${version_name}'] = array('base' => '$(pwd)', 'include' => array(${include_files}));" >> "${SNAPSHOT_TEMPLATE}"
    done < "${CAN_APPLY_FILES}"

}

function publish_snapshot
{
    if [[ ! -d "${PR_NUMBER}" ]]; then
        echo "Error: Directory ${PR_NUMBER} not found!"
        exit 1
    fi
    cd "${PR_NUMBER}" || exit 1

    if [[ ! -f "${SNAPSHOT_TEMPLATE}" ]]; then
        echo "Error: Snapshot file doesn't exists."
        exit 1
    fi

    "${PHP}" "${UCMD_PATH}"/ucommand.php --action="createVersionsAndSnapshot" --file="${WORKSPACE}/${PR_NUMBER}/${SNAPSHOT_TEMPLATE}" --force
}

function trigger_ucd_process
{
    set -x
    if [[ ! -d "${PR_NUMBER}" ]]; then
        echo "Error: Directory ${PR_NUMBER} not found!"
        exit 1
    fi
    cd "${PR_NUMBER}" || exit 1

    cat <<FCH_RUN > application_tmp.json
{
    "application": "${UCD_APPLICATION_NAME}",
    "applicationProcess": "${UCD_APPLICATION_PROCESS}",
    "description": "The process was kicked off from Jenkins.",
    "environment": "$SERVER_ENV",
    "onlyChanged": "false",
    "post-deploy-message": "requestApplicationProcess - deployed quick fix.",
    "snapshot": "${PR_NUMBER}_PR_HOTFIX",
    "properties": {
        "PR_NUMBER":"${PR_NUMBER}"
    }
}
FCH_RUN

    "${PHP}"  "${UCMD_PATH}"/ucommand.php --action=runAndWait --file="${WORKSPACE}/${PR_NUMBER}/application_tmp.json"
}

__main()
{
    case $1 in
        trigger_ucd )
            fun="trigger_ucd_process"
            shift
            while getopts "p:" opt; do
                case $opt in
                    p ) PR_NUMBER="$OPTARG"
                        ;;
                    \?) echo "$0 -p PR_NUMBER"
                        exit 1
                        ;;
                esac
            done
            shift $((OPTIND-1))
            [[ -z "${PR_NUMBER}" ]] && echo "PR is empty: [$0 create_snapshot -p PR_NUMBER]" && exit 1
            ;;
        create_snapshot )
            fun="create_snapshot"
            shift
            while getopts "p:" opt; do
                case $opt in
                    p ) PR_NUMBER="$OPTARG"
                        ;;
                    \?) echo "$0 -p PR_NUMBER"
                        exit 1
                        ;;
                esac
            done
            shift $((OPTIND-1))
            [[ -z "${PR_NUMBER}" ]] && echo "PR is empty: [$0 create_snapshot -p PR_NUMBER]" && exit 1
            ;;
        publish_snapshot )
            fun="publish_snapshot"
            shift
            while getopts "p:" opt; do
                case $opt in
                    p ) PR_NUMBER="$OPTARG"
                        ;;
                    \?) echo "$0 -p PR_NUMBER"
                        exit 1
                        ;;
                esac
            done
            shift $((OPTIND-1))
            [[ -z "${PR_NUMBER}" ]] && echo "PR is empty: [$0 create_snapshot -p PR_NUMBER]" && exit 1
            ;;
        * )
            echo "$(basename ${0}):usage: [create_snapshot|publish_snapshot]"
            exit 1
            ;;
    esac

    ${fun}
}

cd "${WORKSPACE}" || exit 1

__main "$@"

