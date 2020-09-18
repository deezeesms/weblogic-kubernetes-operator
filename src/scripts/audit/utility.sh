#!/bin/bash
# Copyright 2020, Oracle Corporation and/or its affiliates. All rights reserved.
# Licensed under the Universal Permissive License v 1.0 as shown at http://oss.oracle.com/licenses/upl.

#
# Function to note that a validate error has occurred
#
function validationError {
  printError "$*"
  validateErrors=true
}

#
# Function to cause the script to fail if there were any validation errors
#
function failIfValidationErrors {
  if [ "$validateErrors" = true ]; then
    fail 'The errors listed above must be resolved before the script can continue'
  fi
}

#
# Function to exit and print an error message
# $1 - text of message
function fail {
  printError $*
  exit 1
}

# Function to print an error message
function printError {
  echo "[ERROR] $*"
}

# Function to print an info message
function info {
  if [ "$silentMode" == 'true' ]; then
   echo "[INFO] $*" >> "${logFile}"
  else 
   echo "[INFO] $*" | tee -a "${logFile}" 
  fi
}

# Function to print a warning message
function warning {
  echo "[WARNING] $*" | tee -a "${logFile}" 
}

function printReport {
  rowCount=$(cat "${reportFile}" | wc -l)
  if [ $rowCount -gt 1 ]; then
    echo "==============================" | tee -a "${logFile}"
    echo "    Matching Image Details    " | tee -a "${logFile}"
    echo "==============================" | tee -a "${logFile}"
    cat "${reportFile}" | column -t -s ',' |  tee -a "${logFile}"
  else 
    echo "No matching images found."
  fi
}
