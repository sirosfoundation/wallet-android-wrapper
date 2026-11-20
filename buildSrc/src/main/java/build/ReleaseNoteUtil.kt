package build

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.net.URI

fun Project.getLogs(): String {
    val versionCode = property("wallet.versionCode").toString()
    val changelogFile = rootProject.file("fastlane/metadata/android/en-GB/changelogs/$versionCode.txt")

    if (!changelogFile.exists()) {
        throw GradleException(
            "Changelog file not found: ${changelogFile.path}. " +
                "Create it before building or releasing version $versionCode."
        )
    }

    return changelogFile.readText().trim()
}

fun Project.getReleaseQuoteAndAuthor(): Pair<String, String> {
    val body = JsonSlurper().parse(URI.create("https://zenquotes.io/api/random").toURL())
    var quote = ""
    var author = ""

    val item = (body as? List<*>)?.first() as? Map<*, *>?
    if (item?.contains("q") == true) {
        quote = item["q"] as String? ?: "<parser error>"
    }

    if (item?.contains("a") == true) {
        author = item["a"] as String? ?: "<parser error>"
    }

    return quote to author
}

