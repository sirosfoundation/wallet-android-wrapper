package build

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.process.ExecResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

fun env(name: String): String {
    val variable = System.getenv(name)

    if (variable.isNullOrBlank()) {
        throw GradleException("Environment variable '$name' not set or blank. Check build settings.")
    } else {
        return variable
    }
}

fun getHosts(): Set<String> {
    val hosts = mutableSetOf<String>()

    try {
        hosts.add(env("WWWALLET_ANDROID_HOST"))
    }
    catch (e: Exception) {
        // Don't worry about legacy variable.
    }

    try {
        hosts.addAll(env("WWWALLET_ANDROID_HOSTS").split(","))
    }
    catch (e: Exception) {
        e.printStackTrace()
    }

    // The original configuration expected not just a host name, but a schema, too.
    // We replace it, because it gets added later. (HTTPS is enforced!)
    val regex = Regex("https?://")

    //noinspection WrongGradleMethod
    return hosts.map { it.trim().replace(regex, "") }
        .filter { it.isNotBlank() }
        .toMutableSet()
}

fun fileFromEnv(project: Project, envName: String, fileName: String): File {
    val envVar = env(envName)
    val bytes = Base64.getDecoder().decode(envVar)
    val file = project.rootProject.file(fileName)
    file.createNewFile()
    file.writeBytes(bytes)
    return file
}

fun Project.runCommand(command: String): String {
    val output = ByteArrayOutputStream()

    val result: ExecResult? = exec(Action {
        commandLine = listOf("sh", "-c", command)
        standardOutput = output
    }).assertNormalExitValue()

    if (result?.exitValue == 0) {
        return output.toString().lines().filter { it.isNotBlank() }.joinToString("\n")
    }

    throw IllegalStateException("Command '${command}' return exit value: ${result?.exitValue}.")
}
