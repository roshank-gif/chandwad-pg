FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY Main.java .
RUN javac Main.java
ENV DATA_DIR=/data
EXPOSE 8080
CMD ["java", "Main"]
