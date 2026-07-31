#!/bin/bash

# PIN is no longer passed as a command-line argument.
TO_NUMBER=$1
MESSAGE_CONTENT=$2

echo "Injecting SMS to Gammu-SMSD queue for $TO_NUMBER..."
gammu-smsd-inject TEXT "$TO_NUMBER" -text "$MESSAGE_CONTENT"
if [ $? -eq 0 ]; then
    echo "SUCCESS: Message successfully injected into Gammu-SMSD queue. Flag: SMS script completed successfully."
    exit 0
else
    echo "ERROR: Failed to inject message into Gammu-SMSD queue." 1>&2
    exit 1
fi
