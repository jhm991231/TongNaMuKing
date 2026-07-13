#!/bin/bash

# Start nginx
service nginx start

# Install chat-collector dependencies
cd /app/chat-collector && npm install

# Start backend
cd /app
java -Xmx192m -Xms128m \
  -XX:MaxMetaspaceSize=128m \
  -XX:MaxDirectMemorySize=32m \
  -Xss512k \
  -XX:+HeapDumpOnOutOfMemoryError -jar backend.jar &

# Wait for any process to exit
wait -n

# Exit with status of process that exited first
exit $?