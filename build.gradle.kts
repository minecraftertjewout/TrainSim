plugins {
    base
}

tasks.named("build") {
    dependsOn(":app:build")
}