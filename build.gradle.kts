import org.gradle.api.JavaVersion
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.api.tasks.javadoc.Javadoc

plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "io.github.rcgeorge23"

val baseVersion = providers.gradleProperty("baseVersion").get()
version = providers.gradleProperty("releaseVersion")
    .orElse("$baseVersion-SNAPSHOT")
    .get()

java {
    sourceCompatibility = JavaVersion.VERSION_22
    targetCompatibility = JavaVersion.VERSION_22
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 22
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.httpserver"))
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.httpserver")

    val libraryPath = providers.gradleProperty("lightpandaLibrary")
        .orElse(providers.systemProperty("lightpanda.library"))
        .orElse(providers.environmentVariable("LIGHTPANDA_LIBRARY"))
    libraryPath.orNull?.let { systemProperty("lightpanda.library", it) }
}

tasks.register("printBaseVersion") {
    group = "publishing"
    description = "Prints the release base version used by the publication workflow."
    doLast {
        println(baseVersion)
    }
}

mavenPublishing {
    coordinates("io.github.rcgeorge23", "lightpanda-java", version.toString())
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    pom {
        name.set("Lightpanda Java")
        description.set("Experimental Java FFM binding for Lightpanda's proposed C ABI.")
        url.set("https://github.com/rcgeorge23/lightpanda-java")

        licenses {
            license {
                name.set("GNU Affero General Public License v3.0 only")
                url.set("https://www.gnu.org/licenses/agpl-3.0.txt")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("rcgeorge23")
                name.set("rcgeorge23")
                url.set("https://github.com/rcgeorge23")
            }
        }

        scm {
            connection.set("scm:git:https://github.com/rcgeorge23/lightpanda-java.git")
            developerConnection.set("scm:git:ssh://git@github.com/rcgeorge23/lightpanda-java.git")
            url.set("https://github.com/rcgeorge23/lightpanda-java")
        }
    }
}
