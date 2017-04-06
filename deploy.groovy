#!/usr/bin/env groovy

import groovy.json.JsonSlurper

String PR_NUMBER = params.PR_NUMBER;

try {
    node('master') {
        stage('Fetching Code') {
          // Checkout branch
            echo "${WORKSPACE}"
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
        stage ("Generate diff files") {
            echo "Get diff files"
        }
    }
} catch (exec) {
    currentBuild.result = 'FAILURE';
    echo "Caught: ${exc}"
        String msg = """
It appears that ${env.BUILD_URL} is failing.
${exc.getMessage()}
"""
    slackSend channel: env.SLACK_CHANNEL, color: 'danger', message: msg, tokenCredentialId: 'SalesConnect_slack_token'
}
