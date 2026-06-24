@echo off
set "SERVER_PORT=18090"
set "SPRING_DATASOURCE_URL=jdbc:h2:mem:ilink_e2e;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1"
set "SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.h2.Driver"
set "SPRING_DATASOURCE_USERNAME=sa"
set "SPRING_DATASOURCE_PASSWORD="
set "SPRING_FLYWAY_ENABLED=false"
set "SPRING_SQL_INIT_MODE=always"
set "SPRING_SQL_INIT_SCHEMA_LOCATIONS=file:tests/e2e/schema.sql"

mvn -q spring-boot:run -Dspring-boot.run.useTestClasspath=true -Dspring-boot.run.profiles=test
