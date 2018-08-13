import com.sap.piper.Utils
import groovy.transform.Field

import com.sap.piper.ConfigurationLoader
import com.sap.piper.ConfigurationHelper
import com.sap.piper.ConfigurationMerger
import com.sap.piper.ConfigurationType
import com.sap.piper.tools.ToolDescriptor

@Field def STEP_NAME = 'neoDeploy'
@Field Set STEP_CONFIG_KEYS = [
    'account',
    'dockerEnvVars',
    'dockerImage',
    'dockerOptions',
    'host',
    'neoCredentialsId',
    'neoHome'
]

def call(parameters = [:]) {

    Set parameterKeys = [
        'applicationName',
        'archivePath',
        'account',
        'deployMode',
        'dockerEnvVars',
        'dockerImage',
        'dockerOptions',
        'host',
        'neoCredentialsId',
        'neoHome',
        'propertiesFile',
        'runtime',
        'runtimeVersion',
        'vmSize',
        'warAction'
    ]

    handlePipelineStepErrors (stepName: STEP_NAME, stepParameters: parameters) {

        def script = parameters?.script ?: [commonPipelineEnvironment: commonPipelineEnvironment]

        def utils = new Utils()

        prepareDefaultValues script: script

        ConfigurationHelper configHelper = ConfigurationHelper
            .loadStepDefaults(this)
            //.mixinStepConfig(script.commonPipelineEnvironment, STEP_CONFIG_KEYS, this)
            .mixin(parameters, parameterKeys)
            .withMandatoryProperty('host')
            .withMandatoryProperty('account')
            .withMandatoryProperty('neoCredentialsId')
            //.withMandatoryProperty('archivePath')

        final Map stepConfiguration = [:]

        Map configuration = configHelper.use()

        stepConfiguration.putAll(ConfigurationLoader.stepConfiguration(script, STEP_NAME))

        def archivePath = configuration.archivePath
        if(archivePath?.trim()) {
            if (!fileExists(archivePath)) {
                error "Archive cannot be found with parameter archivePath: '${archivePath}'."
            }
        } else {
            error "Archive path not configured (parameter \"archivePath\")."
        }

        def deployHost
        def deployAccount
        def deployMode = configuration.deployMode
        def warAction
        def propertiesFile
        def applicationName
        def runtime
        def runtimeVersion
        def vmSize

        def deployModes = ['mta', 'warParams', 'warPropertiesFile']
        if (! (deployMode in deployModes)) {
            throw new Exception("[neoDeploy] Invalid deployMode = '${deployMode}'. Valid 'deployMode' values are: ${deployModes}.")
        }

        if (deployMode in ['warPropertiesFile', 'warParams']) {
            warAction = utils.getMandatoryParameter(configuration, 'warAction')
            def warActions = ['deploy', 'rolling-update']
            if (! (warAction in warActions)) {
                throw new Exception("[neoDeploy] Invalid warAction = '${warAction}'. Valid 'warAction' values are: ${warActions}.")
            }
        } else if(deployMode == 'mta') {
            warAction = 'deploy-mta'
        }

        if (deployMode == 'warPropertiesFile') {
            propertiesFile = utils.getMandatoryParameter(configuration, 'propertiesFile')
            if (!fileExists(propertiesFile)){
                error "Properties file cannot be found with parameter propertiesFile: '${propertiesFile}'."
            }
        }

        if (deployMode == 'warParams') {
            applicationName = utils.getMandatoryParameter(configuration, 'applicationName')
            runtime = utils.getMandatoryParameter(configuration, 'runtime')
            runtimeVersion = utils.getMandatoryParameter(configuration, 'runtimeVersion')
            def vmSizes = ['lite', 'pro', 'prem', 'prem-plus']
            vmSize = configuration.vmSize
            if (! (vmSize in vmSizes)) {
                throw new Exception("[neoDeploy] Invalid vmSize = '${vmSize}'. Valid 'vmSize' values are: ${vmSizes}.")
            }
        }

        if (deployMode in ['mta','warParams']) {
            deployHost = configuration.host
            deployAccount = configuration.account
        }

        def neo = new ToolDescriptor('SAP Cloud Platform Console Client', 'NEO_HOME', 'neoHome', '/tools/', 'neo.sh', null, 'version')
        def neoExecutable = neo.getToolExecutable(this, configuration)
        def neoDeployScript = """#!/bin/bash
                                 "${neoExecutable}" ${warAction} \
                                 --source "${archivePath}" \
                              """

        if (deployMode in ['mta', 'warParams']) {
            neoDeployScript +=
                """--host '${deployHost}' \
                    --account '${deployAccount}' \
                    """
        }

        if (deployMode == 'mta') {
            neoDeployScript += "--synchronous"
        }

        if (deployMode == 'warParams') {
            neoDeployScript +=
                """--application '${applicationName}' \
                    --runtime '${runtime}' \
                    --runtime-version '${runtimeVersion}' \
                    --size '${vmSize}'"""
        }

        if (deployMode == 'warPropertiesFile') {
            neoDeployScript +=
                """${propertiesFile}"""
        }

        withCredentials([usernamePassword(
            credentialsId: configuration.neoCredentialsId,
            passwordVariable: 'password',
            usernameVariable: 'username')]) {

            def credentials =
                """--user '${username}' \
                   --password '${password}' \
                """
            dockerExecute(dockerImage: configuration.get('dockerImage'),
                dockerEnvVars: configuration.get('dockerEnvVars'),
                dockerOptions: configuration.get('dockerOptions')) {

                neo.verify(this, configuration)

                def java = new ToolDescriptor('Java', 'JAVA_HOME', '', '/bin/', 'java', '1.8.0', '-version 2>&1')
                java.verify(this, configuration)

                sh """${neoDeployScript} \
                      ${credentials}
                   """
            }
        }
    }
}
