plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "io.coralnut.pleosprobe"
    compileSdk = 35
    useLibrary("android.car", false)
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "io.coralnut.pleosprobe"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "io.coralnut.pleosprobe.ProbeInstrumentation"
    }
    buildTypes {
        getByName("debug") { buildConfigField("String", "CORALNUT_API_BASE", "\"${providers.gradleProperty("coralnutApiBase").getOrElse("https://api.muninnmap.com/api")}\"") }
        getByName("release") { buildConfigField("String", "CORALNUT_API_BASE", "\"https://api.muninnmap.com/api\"") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation("com.google.zxing:core:3.5.3")
 implementation("ai.pleos.playground:NaviHelper:3.0.5")
 implementation("ai.pleos.playground:TextToSpeech:3.3.1")
 implementation("ai.pleos.playground:SpeechToText:3.3.1")
}
