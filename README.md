# upscan-verify

upscan-verify microservice is a part of Upscan module. The service is responsible for polling for files uploaded by
users, scanning these files using ClamAV anti-virus software and moving clean files from inbound AWS S3 bucket (where
these were uploaded by users) to outbound S3 bucket (where files can be fetched by the service that initiated upload).
In case file contains virus, the service stores details of the infection in a separate quarantine S3 bucket, and deletes the infected file.

The microservice is internal component of *Upscan* and external users don't have to interact with it.

Detailed documentation of the *Upscan* can be found here: https://github.com/hmrc/upscan-initiate/blob/master/README.md

[![Build Status](https://travis-ci.org/hmrc/upscan-verify.svg)](https://travis-ci.org/hmrc/upscan-verify) [ ![Download](https://api.bintray.com/packages/hmrc/releases/upscan-verify/images/download.svg) ](https://bintray.com/hmrc/releases/upscan-verify/_latestVersion)

# Service configuration

Here is the list of service's configuration properties:

- *aws.useContainerCredentials* - if true, the service will use credentials of hosting EC2 instance to access SQS and SNS (default setting: false)
- *aws.accessKeyId* - AWS key id, not needed when using credentials of hosting EC2 instance
- *aws.secretAccessKey* - AWS secret key, not needed when using credentials of hosting EC2 instance
- *aws.sessionToken* - AWS session token, needed only when service is running locally and uses MFA authentication. Proper value
of the token is set automatically when using aws-profile script
- *aws.sqs.queue.inbound - address of SQS queue that contains notifications regarding uploads of files to inbound bucket 
- *aws.s3.region* - AWS region on which outbound AWS bucket is located (default value is *eu-west-2*)
- *aws.s3.bucket.outbound* - name of the outbound AWS S3 bucket, this bucket will be used to store clean files
- *aws.sqs.retry.interval* - retry period between failed attempts to poll messages from inbound SQS queue

# Running locally

The service relies heavily on AWS infrastructure so that it cannot be run entirely locally.

In order to run the service against one of HMRC accounts (labs, live) you will need to have an AWS accounts with proper
role. See [UpScan Accounts/roles](https://github.com/hmrc/aws-users/blob/master/AccountLinks.md) for details.
The service also requires ClamAV virus scanning software running locally.

## Running ClamAV

ClamAv Version 0.99 or later - the [clam-av client ReadMe](https://github.com/hmrc/clamav-client) provides documentation on how to install or alternatively you can download and run the [docker-clamav image](https://hub.docker.com/r/mkodockx/docker-clamav).

## Setting up AWS credentials

Prerequisites:
- AWS accounts with proper roles setup
- Proper AWS credential configuration set up according to this document [aws-credential-configuration](https://github.com/hmrc/aws-users), with the credentials below:
```
[upscan-service-prototypes-engineer]
source_profile = webops-users
aws_access_key_id = YOUR_ACCESS_KEY_HERE
aws_secret_access_key = YOUR_SECRET_KEY_HERE
output = json
region = eu-west-2
mfa_serial = arn:aws:iam::638924580364:mfa/your.username
role_arn = arn:aws:iam::415042754718:role/RoleServicePrototypesEngineer

[webops-users]
aws_access_key_id = YOUR_ACCESS_KEY_HERE
aws_secret_access_key = YOUR_SECRET_KEY_HERE
mfa_serial = arn:aws:iam::638924580364:mfa/your.username
region = eu-west-2
role_arn = arn:aws:iam::415042754718:role/RoleServicePrototypesEngineer
```
- Working AWS MFA authentication
- Have python 2.7 installed
- Install botocore and awscli python modules locally:
  - For Linux:
```
sudo pip install botocore
sudo pip install awscli
```
  - For Mac (Mac has issues with pre-installed version of ```six``` as discussed [here](https://github.com/pypa/pip/issues/3165):
```
sudo pip install botocore --ignore-installed six
sudo pip install awscli --ignore-installed six
```

## Starting the service locally

In order to run the app against lab environment it's neeeded to run the following commands:
```
export AWS_SQS_QUEUE_INBOUND=name_of_inbound_sqs_queue
export AWS_S3_BUCKET_OUTBOUND=name_of_outbound_s3_bucket
export AWS_DEFAULT_PROFILE=name_of_proper_profile_in_dot_aws_credentials_file
./aws-profile sbt
```
These commands will give you an access to SBT shell where you can run the service using 'run' or 'start' commands.

# Deployment

The service is slightly different from other services running on MDTP platform.
It does'nt use MDTP AWS account nor platform's deployment tools like *Docktor* / *orchestrator* Jenkins.
Instead of the it's deployed into upscan's separate AWS account.

**Important**: When the service runs withing upscan AWS account it takes AWS credentials
from hosting EC2 instance. Using EC2 instace credentials is enabled by setting `aws.useContainerCredentials` property to true.

Here is the process of deploying upscan-verify:
* TODO - to be designed yet

# Tests

Upscan service has end-to-end acceptance tests which can be found in https://github.com/hmrc/upscan-acceptance-tests repository

# License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html")

