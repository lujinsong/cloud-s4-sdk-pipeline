#!/usr/bin/env groovy

final def pipelineSdkVersion = 'master'

pipeline {
    agent any
    options {
        timeout(time: 120, unit: 'MINUTES')
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        skipDefaultCheckout()
    }
    stages {
        stage('Init') {
            steps {
                library "s4sdk-pipeline-library@${pipelineSdkVersion}"
                loadPiper script: this
                /*
                In order to avoid the trust issues between the build server and the git server in a distributed setup,
                the init stage always executes on the master node. The underlying assumption here is that, Jenkins
                server has a ssh key and it has been added to the git server. This is necessary if Jenkins has to push
                code changes to the git server.
                 */
                node('master') {
                    checkout scm
                    initS4SdkPipeline script: this
                }
            }
        }

        stage('Build') {
            parallel {
                stage("Backend") { steps { stageBuildBackend script: this } }
                stage("Frontend") {
                    when { expression { pipelineEnvironment.skipConfiguration.FRONT_END_BUILD } }
                    steps { stageBuildFrontend script: this }
                }
            }
        }

        stage('Local Tests') {
            parallel {
                stage("Static Code Checks") { steps { stageStaticCodeChecks script: this } }
                stage("Backend Unit Tests") { steps { stageUnitTests script: this } }
                stage("Backend Integration Tests") { steps { stageIntegrationTests script: this } }
                stage("Frontend Unit Tests") {
                    when { expression { pipelineEnvironment.skipConfiguration.FRONT_END_TESTS } }
                    steps { stageFrontendUnitTests script: this }
                }
                stage("Node Security Platform Scan") {
                    when { expression { pipelineEnvironment.skipConfiguration.NODE_SECURITY_SCAN } }
                    steps { stageNodeSecurityPlatform script: this }
                }
            }
        }

        stage('Remote Tests') {
            when { expression { pipelineEnvironment.skipConfiguration.REMOTE_TESTS } }
            parallel {
                stage("End to End Tests") {
                    when { expression { pipelineEnvironment.skipConfiguration.E2E_TESTS } }
                    steps { stageEndToEndTests script: this }
                }
                stage("Performance Tests") {
                    when { expression { pipelineEnvironment.skipConfiguration.PERFORMANCE_TESTS } }
                    steps { stagePerformanceTests script: this }
                }
            }
        }

        stage('Quality Checks') {
            steps { stageS4SdkQualityChecks script: this }
        }

        stage('Third-party Checks') {
            when { expression { pipelineEnvironment.skipConfiguration.THIRD_PARTY_CHECKS } }
            parallel {
                stage("Checkmarx Scan") {
                    when { expression { pipelineEnvironment.skipConfiguration.CHECKMARX_SCAN } }
                    steps { stageCheckmarxScan script: this }
                }
                stage("WhiteSource Scan") {
                    when { expression { pipelineEnvironment.skipConfiguration.WHITESOURCE_SCAN } }
                    steps { stageWhitesourceScan script: this }
                }

            }

        }

        stage('Artifact Deployment') {
            when { expression { pipelineEnvironment.skipConfiguration.ARTIFACT_DEPLOYMENT } }
            steps { stageArtifactDeployment script: this }
        }

        stage('Production Deployment') {
            when { expression { pipelineEnvironment.skipConfiguration.PRODUCTION_DEPLOYMENT } }
            steps { stageProductionDeployment script: this }
        }

    }
    post {
        always {
            script {
                if (pipelineEnvironment.skipConfiguration.SEND_NOTIFICATION) {
                    postActionSendNotification script: this
                }
            }
        }
        failure { deleteDir() }
    }
}
