FROM thyrlian/android-sdk AS builder
WORKDIR /app

RUN apt-get update && \
    apt-get install -y python3 python3-pip && \
    rm -rf /var/lib/apt/lists/*

ARG KEY_STORE_PASSWORD
ARG ALIAS
ARG KEY_PASSWORD

ENV KEY_STORE_PASSWORD=$KEY_STORE_PASSWORD
ENV ALIAS=$ALIAS
ENV KEY_PASSWORD=$KEY_PASSWORD
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon assembleRelease
RUN python3 .github/scripts/move-built-apks.py
RUN java -jar Inspector.jar repo/apk output.json tmp
RUN python3 .github/scripts/create-repo.py
FROM nginx:alpine AS final
COPY --from=builder /app/repo /usr/share/nginx/html
EXPOSE 80

