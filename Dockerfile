FROM openjdk:8-alpine

COPY target/uberjar/shade.jar /shade/app.jar

EXPOSE 3000

CMD ["java", "-jar", "/shade/app.jar"]
