# ---- 构建阶段：Node 22 Alpine 打包 Vite 产物 ----
FROM node:22-alpine AS build
WORKDIR /web

# 先装依赖，利用镜像层缓存
COPY package*.json ./
COPY .npmrc ./
RUN npm ci --registry=https://registry.npmmirror.com

# 拷贝源码并构建
COPY . .
RUN npm run build

# ---- 运行阶段：Nginx 1.27 Alpine 托管 dist + /api 反代 + SSE location ----
FROM nginx:1.27-alpine
COPY --from=build /web/dist /usr/share/nginx/html
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
