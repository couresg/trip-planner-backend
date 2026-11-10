plugins {
	java
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.tripplanner"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.flywaydb:flyway-mysql")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0")
	implementation("com.github.ben-manes.caffeine:caffeine")
	implementation("com.bucket4j:bucket4j_jdk17-core:8.21.0")
	runtimeOnly("com.mysql:mysql-connector-j")

	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-mysql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 에이전트 도구가 리소스 폴더에 남긴 로컬 상태(.omc)가 jar에 들어가지 않게 한다.
tasks.processResources {
	exclude("**/.omc/**")
}

// 기본 테스트는 Docker/DB 없이 실행한다.
// Testcontainers가 필요한 테스트는 @Tag("integration")을 붙이고 integrationTest로 실행한다.
tasks.test {
	useJUnitPlatform {
		excludeTags("integration")
	}
}

tasks.register<Test>("integrationTest") {
	description = "Testcontainers(MySQL)가 필요한 통합 테스트 (Docker 필요)"
	group = "verification"
	testClassesDirs = sourceSets["test"].output.classesDirs
	classpath = sourceSets["test"].runtimeClasspath
	useJUnitPlatform {
		includeTags("integration")
	}
}
