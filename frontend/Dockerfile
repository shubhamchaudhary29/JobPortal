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

# Fail the build if envsubst (used to render nginx.conf.template) is ever dropped from the base image.
RUN command -v envsubst

# Rate-limit zones (http context, loaded before default.conf) and the shared server snippets.
COPY nginx-limits.conf /etc/nginx/conf.d/00-limits.conf
COPY nginx.conf.template nginx-http.conf jobportal-locations.conf proxy-backend.conf security-headers.conf \
     cloudflare-realip.conf /etc/nginx/jobportal/
RUN mv /etc/nginx/jobportal/jobportal-locations.conf /etc/nginx/jobportal/locations.conf

# Render HTTPS when the deployment certificate is mounted; otherwise use local HTTP.
COPY select-nginx-config.sh /docker-entrypoint.d/05-select-jobportal-config.sh
RUN chmod 755 /docker-entrypoint.d/05-select-jobportal-config.sh

# Copy built assets
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
