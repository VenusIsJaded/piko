android {
    namespace = "app.morphe.extension.instagram"

    defaultConfig {
        minSdk = 26
    }

    lint {
        abortOnError = false
        disable += "MissingSuperCall"
    }
}

dependencies {
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:instagram:stub"))
    compileOnly(libs.morphe.extensions.library)
    compileOnly(libs.annotation)
    compileOnly(libs.appcompat)
}
