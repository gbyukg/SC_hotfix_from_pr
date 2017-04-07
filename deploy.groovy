#!/usr/bin/env groovy

import groovy.json.JsonSlurper

def WORK_SPACE_ROOT = "${env.JENKINS_HOME}/workspace/${env.JOB_NAME}"
def SCRIPT_PATH = "${WORK_SPACE_ROOT}@script";
def PATCH_FILE = "${WORK_SPACE_ROOT}/PATCHS"
def UTIL_SC = "${SCRIPT_PATH}/util.py"
def UCD_SC = "${SCRIPT_PATH}/ucd_util.sh"

def PR_NUMBER = params.PR_NUMBER;

def github_api(String cmd, String pr_number, String returnType = 'getStdout')
{
    String return_val;
        withCredentials([string(credentialsId: 'github_token', variable: 'github_token')]) {
            try {
                if (returnType == 'getStdout') {
                    return_val =  sh(
                        returnStdout: true,
                        script: "python ${cmd} -p ${pr_number} -t ${github_token}"
                    ).trim();
                } else {
                    return_val =  sh(
                        returnStatus: true,
                        script: "python ${cmd} -p ${pr_number} -t ${github_token}"
                    );
                }
            } catch (hudson.AbortException e) {
                if (e.getMessage().contains('script returned exit code 9')) {
                    error "Failed: PR number is empty.";
                } else if (e.getMessage().contains('script returned exit code 10')) {
                    error "Failed: Can't not find your pull request, please confirm if you typed PR is appropriate.";
                } else if (e.getMessage().contains('script returned exit code 11')){
                    erro ("Can't not find your pull request, please confirm if you typed PR is appropriate.");
                } else if(e.getMessage().contains('script returned exit code 12')) {
                    error ("Undefined error found, return value: 12")
                } else if (! pr_number) {
                    error "Pll rquest number can't be empty, please provide your PR number.";
                } else if (! pr_number.isNumber()) {
                    error "[*${pr_number}*] is not a validated pull request number.";
                } else if (! github_token) {
                    error "Github API token is empty, @zzlzhang, please take a look at this.... ";
                } else if(e.getMessage().contains('script returned exit code 10')){
                    error "Wrong parameters for githug_api script"
                } else {
                    throw e
                }
            }
        };
    return return_val;
}

try {
    node('master') {
        stage('Getting PR info') {
            def return_val = null;
            return_val = github_api("${UTIL_SC} pr", PR_NUMBER);

            def jsonSlurper = new JsonSlurper();
            def pr_info = jsonSlurper.parseText(return_val);

            // The PR has not been merged yet, we can't apply the patch to SVT.
            echo pr_info.url
            if (!pr_info.merged) {
                jsonSlurper = null;
                pr_info = null;
                def msg = "The PR [${PR_NUMBER} https://github.com/sugareps/Mango/pull/${PR_NUMBER}] has not been merged, you can't deploy this PR into SVT server";
                echo msg
                send_msg_slack("danger", msg);
                error msg;
            }

            PR_TITLE = pr_info.title
            PR_URL = pr_info.url
            PR_STATE = pr_info.state
            PR_MERGED_USER = pr_info.merged_by.login
            PR_BASE_SHA = pr_info.base.sha
            PR_BASH_REF = pr_info.base.ref
            PR_HEAD_SHA = pr_info.head.sha
            PR_HEAD_REF = pr_info.head.ref
            PR_HEAD_LABEL = pr_info.head.label
            PR_BASE_LABEL = pr_info.base.label

            echo """
            PR info [${PR_NUMBER}]:
            Title: ${PR_TITLE}
            ${PR_HEAD_LABEL} -> ${PR_BASE_LABEL}
            PR URL: ${PR_URL}
            PR state: ${PR_STATE}
            Merged by: ${PR_MERGED_USER}
            Base sha: ${PR_BASE_SHA}
            Head sha: ${PR_HEAD_SHA}
            """

        }
        stage('Fetching Code') {
            // Checkout branch
            checkout(
                [
                    $class: 'GitSCM',
                    branches: [
                        [name: "sugareps/pr/${PR_NUMBER}"]
                    ],
                    doGenerateSubmoduleConfigurations: false,
                    extensions: [
                        [$class: 'CloneOption',
                            depth: 50,
                            noTags: false,
                            reference: '',
                            shallow: false,
                            timeout: 20
                        ],
                        [$class: 'CleanBeforeCheckout'],
                        [
                            $class: 'RelativeTargetDirectory',
                            relativeTargetDir: 'Mango'
                        ],
                        [
                            $class: 'SubmoduleOption',
                            disableSubmodules: false,
                            parentCredentials: true,
                            recursiveSubmodules: true,
                            reference: '',
                            trackingSubmodules: false
                        ],
                        [$class: 'DisableRemotePoll']
                    ],
                    submoduleCfg: [],
                    userRemoteConfigs: [
                        [
                            credentialsId: 'github_ssh_key',
                            name: 'origin',
                            refspec: '+refs/pull/*/head:refs/remotes/sugareps/pr/* +refs/heads/*:refs/remotes/origin/*',
                            url: 'git@github.com:sugareps/Mango.git'
                        ]
                    ]
                ]
            );
        }
        stage ("Generate compare diff files") {
            echo "Get diff files"
            def cmd = "${UTIL_SC} patch --applied-file-name ${env.CAN_APPLY_FILES} --mongo-dir ${WORKSPACE}/Mango --base ${PR_BASE_SHA} --head ${PR_HEAD_SHA}"
            if (! github_api(cmd, PR_NUMBER, 'getStatus')) {
                error "Create fix package failed."
            }
        }
        stage ("Create snapshot template") {
            returnCode = sh returnStatus: true, script: "${UCD_SC} create_snapshot -p ${PR_NUMBER}"
            if (returnCode != 0) {
                error "Create snapshot failed."
            }
        }
        stage ("Publish snapshot") {
            String PHP = tool name: 'php', type: 'com.cloudbees.jenkins.plugins.customtools.CustomTool'
            withEnv(["PHP=${PHP}/bin/php"]) {
                returnCode = sh returnStatus: true, script: "${UCD_SC} publish_snapshot -p ${PR_NUMBER}"
            }
            if (returnCode != 0) {
                error "Publish snapshot failed."
            }
        }
        stage ("Trigger UCD process") {
            String PHP = tool name: 'php', type: 'com.cloudbees.jenkins.plugins.customtools.CustomTool'
            withEnv(["PHP=${PHP}/bin/php"]) {
                returnCode = sh returnStatus: true, script: "${UCD_SC} trigger_ucd -p ${PR_NUMBER}"
            }
            if (returnCode != 0) {
                error "Create snapshot failed."
            }
        }
    }
} catch (exec) {
    currentBuild.result = 'FAILURE';
    echo "Caught: ${exec}"
        String msg = """
It appears that ${env.BUILD_URL} is failing.
${exec.getMessage()}
"""
    slackSend channel: env.SLACK_CHANNEL, color: 'danger', message: msg, tokenCredentialId: 'SalesConnect_slack_token'
}
