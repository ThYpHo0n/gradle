dependencies {
    implementation("me.champeau.gradle:jmh-gradle-plugin:0.4.7")
    implementation("org.jsoup:jsoup:1.11.3")
    implementation("com.gradle:build-scan-plugin:2.0-rc-3-20181016094325-release")
    implementation(project(":configuration"))
    implementation(project(":kotlinDsl"))
}

gradlePlugin {
    plugins {
        register("buildscan") {
            id = "gradlebuild.buildscan"
            implementationClass = "org.gradle.gradlebuild.profiling.buildscan.BuildScanPlugin"
        }
    }
}
