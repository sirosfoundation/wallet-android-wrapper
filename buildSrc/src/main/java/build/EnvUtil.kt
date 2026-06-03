package build

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.process.ExecOperations
import org.gradle.kotlin.dsl.support.serviceOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

fun Project.env(name: String): String {
    val variable = System.getenv(name) ?: run {
        val localProps = java.util.Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) localPropsFile.inputStream().use { localProps.load(it) }
        localProps.getProperty(name)
    }

    if (variable.isNullOrBlank()) {
        throw GradleException("'$name' not set. Add it as an environment variable or to local.properties.")
    } else {
        return variable
    }
}

fun Project.fileFromEnv(project: Project, envName: String, fileName: String): File {
    val envVar = env(envName)
    val bytes = Base64.getDecoder().decode(envVar)
    val file = project.rootProject.file(fileName)
    file.createNewFile()
    file.writeBytes(bytes)
    return file
}

fun Project.runCommand(command: String): String {
    val execOperations = project.serviceOf<ExecOperations>()
    val output = ByteArrayOutputStream()

    val result = execOperations.exec {
        commandLine = listOf("sh", "-c", command)
        standardOutput = output
    }.assertNormalExitValue()

    if (result.exitValue == 0) {
        return output.toString().lines().filter { it.isNotBlank() }.joinToString("\n")
    }

    throw IllegalStateException("Command '${command}' return exit value: ${result.exitValue}.")
}
