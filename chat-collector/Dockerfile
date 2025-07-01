FROM node:18-slim

WORKDIR /app

# Copy package files
COPY package*.json ./

# Install dependencies
RUN npm install

# Copy source code
COPY . .

# Expose port (optional - for monitoring)
EXPOSE 3000

# Run the chat collector
CMD ["node", "index.js"]