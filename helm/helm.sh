#!/bin/bash

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
EXTENSION_ROOT_DIR=${PWD}
CONTEXT_DIR=""
HELM_CHARTS_VERSION="2.0"
GITHUB_ORGANIZATION="AppdAgents"
GITHUB_PASSWORD=""
GITHUB_USERNAME="AppdAgents"
GITHUB_REPO_URL="https://appdagents.github.io/k8s-extension-charts/"

function createContextDirectory() {
    CONTEXT_DIR=$DIR/context
    printf $CONTEXT_DIR
    rm -rf $CONTEXT_DIR

    mkdir -p $CONTEXT_DIR
    mkdir -p $CONTEXT_DIR/k8s-extension
    mkdir -p $CONTEXT_DIR/k8s-charts
    mkdir -p $CONTEXT_DIR/packaged-k8s-charts
    mkdir -p $EXTENSION_ROOT_DIR/packaged-helmcharts

    printf "\nDirectories created ...\n"
}
# copy the resources that we want to upload. Copying helm folder from codebase into k8s-extension and k8s-extension-charts sub-directories
function copyResourcesIntoSubDirectories() {
    cp -a $EXTENSION_ROOT_DIR/k8s-extension/. $CONTEXT_DIR/k8s-extension
    printf "\nCopied resources into k8s-extension folder ...\n"
    cp -a $EXTENSION_ROOT_DIR/k8s-extension/. $CONTEXT_DIR/k8s-charts
    printf "\nCopied resources into k8s_extension-charts folder ...\n"
}


# update the helm dependencies of the k8s-extension helm chart
function helmUpdateDependency() {
  cd $CONTEXT_DIR
  helm dependency update k8s-extension
}

# build the helm dependencies of the k8s-extension helm chart
function helmBuildDependency() {
  cd $CONTEXT_DIR
  helm dependency build k8s-extension
}

# create the k8s-extension helm chart package
function createK8sExtensionPackage() {
    helm package k8s-extension --destination $CONTEXT_DIR/packaged-k8s-charts
}

# remove temporary directory context
function removeContextDirectory() {
    rm -rf $CONTEXT_DIR
}

# clone the k8s-extension-charts repo
function cloneAppdHelmChartsRepo() {
    git clone "https://$GITHUB_USERNAME:$GITHUB_PASSWORD@github.com/$GITHUB_ORGANIZATION/k8s-extension-charts.git"
    printf '\nCloned the k8s-extension-charts repo into context directory .... \n'
}

# generate the index file
function generateIndexFile() {
    helm repo index --url $GITHUB_REPO_URL --merge k8s-extension-charts/index.yaml  k8s-extension-charts/
}

# upload the updated files in the k8s-extension-charts/k8s-extension folder and upload the packaged k8s-extension
# helm chart and index.yaml file
function uploadUpdatedFiles() {
    cp packaged-k8s-charts/* $EXTENSION_ROOT_DIR/packaged-helmcharts
    mv packaged-k8s-charts/* k8s-extension-charts

    rm -rf k8s-extension-charts/k8s-extension/*
    mkdir -p k8s-extension-charts/k8s-extension
    mv k8s-extension/* k8s-extension-charts/k8s-extension
}

function setGitConfig() {
    cd $CONTEXT_DIR/k8s-extension-charts
    git config user.email "appd.agents@appdynamics.com"
    git config user.name "AppdAgents"
}
# upload the updated files in the k8s-extension-charts/k8s-extension-agent folder and upload the packaged k8s-extension
# helm chart and index.yaml file

# push the changes to k8s-extension-charts repo
function pushToAppdHelmChartsRepo() {
    git add "k8s-extension-$HELM_CHARTS_VERSION.tgz"
    git commit -m "Released helm chart $HELM_CHARTS_VERSION for extension"
    git add index.yaml
    git commit -m "Updated the file on $HELM_CHARTS_VERSION Release"
    git add .
    git commit -m "latest extension charts"
    This should be uncommented to publish charges
   if git  push origin master --force
   then
    printf '\nSuccessfully uploaded the files on k8s-extension-charts github repo \n'
   else
    printf "\ngit push failed\n"
   fi
    cd ../..
}
function createHelmCharts() {
  createContextDirectory
  copyResourcesIntoSubDirectories
  helmUpdateDependency
  helmBuildDependency
  createK8sExtensionPackage
  cloneAppdHelmChartsRepo
  generateIndexFile
  uploadUpdatedFiles
  setGitConfig
  pushToAppdHelmChartsRepo
  removeContextDirectory
}

$@;
