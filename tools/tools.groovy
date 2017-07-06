println("TOOLS - START")
import groovy.json.JsonSlurperClassic


/**
 * Zip lambda files in 'folder' for S3 upload
 * @param folder
 * @param serviceName
 * @return none
 */
def zipLambda(String folder, String serviceName) {
    dir(folder) {

        //Loop Through and zip functions in $folder
        exec =
            """
            for f in *.js; do \n
            sudo zip ${serviceName}_\${f%.js} \${f} \n
            done
            """

        sh exec
    }
}


/**
 * Send build status to slack to notify team
 * @param buildStatus
 * @return none
 */
def notifyBuild(String buildStatus = 'STARTED') {
println("notifyBuild - START")
    // Build status of null means successful
    buildStatus =  buildStatus ?: 'SUCCESSFUL'

    // Default values
    subject = "${buildStatus}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]: ${release_folder}'"
    summary = "${subject} (${env.BUILD_URL})"

    // Override default values based on build status
    if (buildStatus == 'STARTED') {
        color = 'YELLOW'
        colorCode = '#FFFF00'
    } else if (buildStatus == 'SUCCESSFUL') {
        color = 'GREEN'
        colorCode = '#00FF00'
    } else {
        color = 'RED'
        colorCode = '#FF0000'
    }

    // Send notifications
    slackSend (color: colorCode, message: summary)
}


/**
 * Deploy Api Gateway
 * @param environment
 * @param serviceName
 * @return none
 */
def redeployApi(String environment, String serviceName) {

    //Define CLI Commands, since service stack names have 'service' identifier in name
    stackName = (serviceName == 'serviceHealth' || serviceName == 'managementConsole')?
            "${environment}-${serviceName}-API-Stack" : "${environment}-${serviceName}-service-API-Stack"

    script = "aws cloudformation list-stack-resources --stack-name ${stackName}"

    //Retrieve list of resources for current stack from AWS
    describeResources = sh (
            script: "${script}",
            returnStdout: true
    ).trim()

    //Parse JSON Response From CLI Call
    resources = new JsonSlurperClassic().parseText(describeResources)

    //Get PhysicalResourceId From Parsed Object
    for (item in resources.StackResourceSummaries) {
        if (item.containsValue("AWS::ApiGateway::RestApi")){
            restID = item.get("PhysicalResourceId")
        }
    }

    //Get Current Date/Time
    time = new Date()

    //Deploy API Gateway
    sh "aws apigateway create-deployment --rest-api-id ${restID} --stage-name ${environment} --description 'Jenkins initiated ${release_folder} Deploy ${time}' --region us-west-2"
}

/**
 * Verify that CFT contains changes for stack
 * @param environment
 * @param serviceName
 * @param releaseFolder
 * @param jenkinsCFTPath
 * @return bool true/false
 */
boolean CFTHasChanges(String environment, String serviceName, String releaseFolder, String CFTPath){

    //Versioned Parameter File
    paramsJson = "${serviceName}_${releaseFolder}_params.json"

    //Define CLI Commands, since service stack names have 'service' identifier in name
    stackName = (serviceName == 'serviceHealth' || serviceName == 'managementConsole')?
            "${environment}-${serviceName}-API-Stack" : "${environment}-${serviceName}-service-API-Stack"

    createChangeSet = "aws cloudformation create-change-set --stack-name ${stackName} --template-url ${CFTPath} --parameters file://${paramsJson} --change-set-name ${environment}-${serviceName}-change-set --capabilities CAPABILITY_NAMED_IAM"

    describeChangeSet = "aws cloudformation describe-change-set --change-set-name ${environment}-${serviceName}-change-set --stack-name ${stackName}"

    deleteChangeSet = "aws cloudformation delete-change-set --change-set-name ${environment}-${serviceName}-change-set --stack-name ${stackName}"

    describeAllStacksCMD = "aws cloudformation describe-stacks"

    //Remove All Previous Parameter Files
    sh "sudo rm -f *_params.json"


    //Populate Params File
    if (serviceName == 'serviceHealth' || serviceName == 'managementConsole') {
        sh "echo '[{\"ParameterKey\": \"paramEnvironment\",\"ParameterValue\": \"${environment}\"},{\"ParameterKey\": \"paramServiceName\",\"ParameterValue\": \"intellicloud-api\"},{\"ParameterKey\": \"paramReleaseFolder\",\"ParameterValue\": \"${releaseFolder}\"}]' >> ${WORKSPACE}/${paramsJson}"
    } else {
        sh "echo '[{\"ParameterKey\": \"paramEnvironment\",\"ParameterValue\": \"${environment}\"},{\"ParameterKey\": \"paramServiceName\",\"ParameterValue\": \"${serviceName}-service\"},{\"ParameterKey\": \"paramReleaseFolder\",\"ParameterValue\": \"${releaseFolder}\"}]' >> ${WORKSPACE}/${paramsJson}"
    }

    //Create Change Set with Params
    describeAllStacks = sh (
            script: "${describeAllStacksCMD}",
            returnStdout: true
    ).trim()

    if (describeAllStacks.contains("${stackName}")){
        sh "${createChangeSet}"
    } else {
        return true
    }

    //Get Change Set Status
    changeSet = sh (
            script: "${describeChangeSet}",
            returnStdout: true
    ).trim()

    //Wait until Change Set is finished being created
    while (changeSet.contains("CREATE_IN_PROGRESS") || changeSet.contains("CREATE_PENDING")) {
        sleep 15

        changeSet = sh (
                script: "${describeChangeSet}",
                returnStdout: true
        ).trim()

        print "${serviceName} Change Set Create Status: CREATE_IN_PROGRESS"
    }

    //If Status indicates no changes return false
    if (changeSet.contains("The submitted information didn't contain changes")){
        return false
        //delete change set to avoid duplicate error in the future
        sh "${deleteChangeSet}"
    } else {
        return true
    }
}

/**
 * Deploys change set created by CFTHasChanges
 * @param environment
 * @param serviceName
 */
def deployCFTChanges(String environment, String serviceName, String CFTPath) {

    //Versioned Parameter File
    paramsJson = "${serviceName}_${release_folder}_params.json"

    //Define CLI Commands, since service stack names have 'service' identifier in name
    stackName = (serviceName == 'serviceHealth' || serviceName == 'managementConsole')?
            "${environment}-${serviceName}-API-Stack" : "${environment}-${serviceName}-service-API-Stack"

    createStack = "aws cloudformation create-stack --template-url ${CFTPath} --stack-name ${stackName} --parameters file://${paramsJson} --capabilities CAPABILITY_NAMED_IAM"

    updateStack = "aws cloudformation execute-change-set --change-set-name ${environment}-${serviceName}-change-set --stack-name ${stackName}"

    describeAllStacksCMD = "aws cloudformation describe-stacks"

    describeThisStackCMD = "aws cloudformation describe-stacks --stack-name ${stackName}"

    deleteChangeSet = "aws cloudformation delete-change-set --change-set-name ${environment}-${serviceName}-change-set --stack-name ${stackName}"

    //Get Stack Update Status
    hasChangeSet = false

    describeAllStacks = sh (
            script: "${describeAllStacksCMD}",
            returnStdout: true
    ).trim()

    if (describeAllStacks.contains("${stackName}")){
        //If stack exists, update stack
        sh "${updateStack}"

        describeThisStack = sh (
                script: "${describeThisStackCMD}",
                returnStdout: true
        ).trim()

        //set change set to true for delete later
        hasChangeSet = true
    } else {
        //If stack does not exist create stack
        sh "${createStack}"

        describeThisStack = sh (
                script: "${describeThisStackCMD}",
                returnStdout: true
        ).trim()
    }

    //Wait until stack is finished updating
    while(describeThisStack.contains("UPDATE_IN_PROGRESS") || describeThisStack.contains("CREATE_IN_PROGRESS")) {

        print "${serviceName} Stack Deploy/Update Execution In Progress"

        describeThisStack = sh (
            script: "${describeThisStackCMD}",
            returnStdout: true
        ).trim()

        sleep 15
    }

    //If Change Set Failed to Update Stack Throw Error
    if(describeThisStack.contains("FAILED") || describeThisStack.contains("ROLLBACK")) {
        print "${serviceName} CFT Failed To Deploy"

        throw new Exception(describeThisStack)
    } else if(hasChangeSet){
        //If it was created and there is no error delete change set
        sh "${deleteChangeSet}"
    }
}

return this
