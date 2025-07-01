#!/bin/bash

# Start nginx
service nginx start

# Install chat-collector dependencies
cd /app/chat-collector && npm install

# Start backend
cd /app
java -jar backend.jar &

# Wait for any process to exit
wait -n

# Exit with status of process that exited first
exit $?