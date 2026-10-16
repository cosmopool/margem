plugins { `java-library` }

group = "com.kaiodelphino.margem"

dependencies { compileOnly(libs.pitest) }

tasks.withType<JavaCompile>().configureEach { options.release = 17 }
