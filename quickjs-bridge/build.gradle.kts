plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(libs.quickjs.java)
    api(libs.gson)
    api(libs.json.org)
    api(project.dependencies.platform(libs.okhttp.bom))
    api(libs.okhttp)
    implementation(libs.guava)
    implementation(libs.jsoup)
    implementation(libs.jsoupxpath)
    implementation("org.apache.commons:commons-lang3:3.14.0")
    implementation("org.slf4j:slf4j-api:2.0.13")
}
