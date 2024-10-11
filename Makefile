SHELL := /usr/bin/env bash
DOCKER_OK := $(shell type -P docker)
ARTIFACTORY_HOST := artefacts.tax.service.gov.uk
ARTEFACT := upscan-verify
# to build a SNAPSHOT locally set MAKE_RELEASE to false
MAKE_RELEASE := true
MAKE_HOTFIX ?= false
tag = $$(cat ./tmp/RELEASE_VERSION)

build:
	@echo "****** building ${ARTEFACT} ******"
	docker run --name docker-platops-sbt -d -i -t --rm -v .:/root/build artefacts.tax.service.gov.uk/docker-platops-sbt /bin/bash
	docker exec -w /root docker-platops-sbt cp /root/project/build.properties /root/build/project/build.properties
	docker exec -w /root/build docker-platops-sbt sbt clean test
	docker exec -w /root/build docker-platops-sbt sbt docker:stage
	docker exec -e MAKE_RELEASE=${MAKE_RELEASE} -e MAKE_HOTFIX=${MAKE_HOTFIX} -e VERSION_FILENAME="./tmp/RELEASE_VERSION" -w /root/build docker-platops-sbt sbt writeVersion
	docker build -t ${ARTIFACTORY_HOST}/$(ARTEFACT):$(tag) ./target/docker/stage
	docker stop docker-platops-sbt

authenticate_to_artifactory:
	@docker login --username ${ARTIFACTORY_USERNAME} --password "${ARTIFACTORY_PASSWORD}" ${ARTIFACTORY_HOST}

push:
	docker push ${ARTIFACTORY_HOST}/$(ARTEFACT):$(tag)

environment:
	git describe --tags --first-parent --abbrev=0
	printenv
