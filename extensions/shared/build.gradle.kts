android {
    namespace = "app.morphe.extension"

    defaultConfig {
        minSdk = 28
    }

    lint {
        abortOnError = false
        disable += listOf("MissingSuperCall", "NotificationPermission")
    }
}

dependencies {
    implementation(project(":extensions:shared:library"))
}
