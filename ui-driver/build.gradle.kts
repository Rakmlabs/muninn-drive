plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "io.coralnut.pleosuidriver"
 compileSdk = 35
 defaultConfig { applicationId = "io.coralnut.pleosuidriver"; minSdk = 28; targetSdk = 34 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
