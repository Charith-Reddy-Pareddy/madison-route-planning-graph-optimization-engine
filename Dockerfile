# --- frontend build ---
FROM node:22-alpine AS frontend-build
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# --- java build ---
FROM eclipse-temurin:21-jdk-alpine AS java-build
WORKDIR /app
COPY src/ src/
RUN javac -d out/classes src/*.java

# --- runtime ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=java-build /app/out/classes ./classes
COPY --from=frontend-build /app/web ./web
COPY data/ ./data/
EXPOSE 8080
CMD ["java", "-cp", "classes", "Main"]
