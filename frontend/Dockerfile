FROM node:20-alpine AS builder

WORKDIR /app

# Cache dependencies
COPY package.json package-lock.json ./
RUN npm ci

# Build the production bundle
COPY . .
RUN npm run build

# --- Runtime: serve with Nginx ---
FROM nginx:alpine

# Remove default Nginx config
RUN rm /etc/nginx/conf.d/default.conf

# Select TLS when the deployment certificate is mounted; otherwise use local HTTP.
COPY nginx.conf /etc/nginx/jobportal-https.conf
COPY nginx-http.conf /etc/nginx/jobportal-http.conf
COPY select-nginx-config.sh /docker-entrypoint.d/05-select-jobportal-config.sh
RUN chmod 755 /docker-entrypoint.d/05-select-jobportal-config.sh

# Copy built assets
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
