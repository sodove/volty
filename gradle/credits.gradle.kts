import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.provider.ListProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import java.security.MessageDigest

abstract class CreditsGraphTask : DefaultTask() {
    @get:Input abstract val coordinates: ListProperty<String>
    @get:OutputFile abstract val graph: RegularFileProperty
    @TaskAction fun write() {
        graph.get().asFile.apply {
            parentFile.mkdirs()
            writeText(JsonOutput.prettyPrint(JsonOutput.toJson(coordinates.get().sorted())) + "\n")
        }
    }
}

abstract class VerifyCreditsTask : DefaultTask() {
    @get:Input abstract val coordinates: ListProperty<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalog: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection
    @get:Internal abstract val repositoryRoot: DirectoryProperty

    @TaskAction fun verify() {
        val root = repositoryRoot.get().asFile.toPath().toAbsolutePath().normalize()
        val data = JsonSlurper().parse(catalog.get().asFile) as Map<*, *>
        val sources = data["sources"] as? Map<*, *> ?: error("Credits catalog has no provenance")
        require((sources["graph"] as? List<*>) == coordinates.get().sorted()) {
            "Credits dependency graph changed. Refresh the credits catalog; see tools/credits/README.md"
        }
        val expected = sources["inputs"] as? Map<*, *> ?: error("Credits input hashes missing")
        require(expected.isNotEmpty()) { "Credits input hashes empty" }
        expected.forEach { (path, hash) ->
            val file = root.resolve(path.toString()).normalize()
            require(file.startsWith(root) && file.toFile().isFile) { "Missing credits source: $path" }
            val bytes = file.toFile().readText().replace("\r\n", "\n").replace("\r", "\n").toByteArray(Charsets.UTF_8)
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(actual == hash) { "Credits source changed: $path. Refresh credits catalog." }
        }
        val binaries = sources["binaries"] as? Map<*, *> ?: error("Credits binary hashes missing")
        val localArtifacts = coordinates.get().filter { it.startsWith("file:") }.map { it.removePrefix("file:") }.toSet()
        require(binaries.keys.map { it.toString().substringAfterLast('/') }.toSet() == localArtifacts) {
            "Credits local archive inventory changed. Refresh credits catalog."
        }
        binaries.forEach { (path, hash) ->
            val file = root.resolve(path.toString()).normalize()
            require(file.startsWith(root) && file.toFile().isFile) { "Missing credits archive: $path" }
            val actual = MessageDigest.getInstance("SHA-256").digest(file.toFile().readBytes())
                .joinToString("") { "%02x".format(it) }
            require(actual == hash) { "Credits archive changed: $path. Refresh credits catalog." }
        }
        val projects = data["projects"] as? List<*> ?: error("Credits projects missing")
        val licenses = data["licenses"] as? Map<*, *> ?: error("Credits licenses missing")
        require(projects.isNotEmpty()) { "Credits catalog is empty" }
        val ids = mutableSetOf<String>()
        projects.forEach { item ->
            val p = item as Map<*, *>
            require(ids.add(p["id"].toString())) { "Duplicate credits project: ${p["id"]}" }
            val refs = p["licenseIds"] as? List<*> ?: error("Missing licenses for ${p["id"]}")
            require(refs.isNotEmpty()) { "Missing licenses for ${p["id"]}" }
            refs.forEach { id ->
                val license = licenses[id] as? Map<*, *> ?: error("Missing license: $id")
                require(!license["content"].toString().isBlank() && license["content"] != null) {
                    "Empty license: $id"
                }
            }
        }
        logger.lifecycle("Verified credits: ${projects.size} projects; ${licenses.size} offline license texts")
    }
}

val creditsCoordinates = providers.provider {
    configurations.getByName("releaseRuntimeClasspath").incoming.artifacts.artifacts.map { artifact ->
        val id = artifact.id.componentIdentifier
        if (id is ModuleComponentIdentifier) "${id.group}:${id.module}:${id.version}"
        else "file:${artifact.file.name}"
    }.distinct().sorted()
}

tasks.register<CreditsGraphTask>("writeCreditsDependencyGraph") {
    group = "documentation"
    coordinates.set(creditsCoordinates)
    graph.set(layout.buildDirectory.file("reports/credits/dependencies.json"))
}

val verifyCredits = tasks.register<VerifyCreditsTask>("verifyCreditsCatalog") {
    group = "verification"
    description = "Rejects stale dependency/notice metadata and missing offline license texts"
    coordinates.set(creditsCoordinates)
    catalog.set(layout.projectDirectory.file("src/commonMain/composeResources/files/credits/catalog.json"))
    repositoryRoot.set(rootProject.layout.projectDirectory)
    sourceFiles.from(rootProject.fileTree("third-party/credits"))
    sourceFiles.from(rootProject.file("THIRD_PARTY_NOTICES.md"))
    val inputsRoot = rootProject.projectDir
    sourceFiles.from(catalog.map { snapshot ->
        if (!snapshot.asFile.isFile) emptyList<File>() else {
            val data = JsonSlurper().parse(snapshot.asFile) as Map<*, *>
            val sources = data["sources"] as? Map<*, *> ?: emptyMap<Any, Any>()
            listOf("inputs", "binaries").flatMap { key ->
                (sources[key] as? Map<*, *>)?.keys.orEmpty().map { inputsRoot.resolve(it.toString()) }
            }
        }
    })
    // This validator has no outputs and must re-read the tracked originals on every build.
    outputs.upToDateWhen { false }
}

// The checked-in offline snapshot must match the distribution being built.
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyCredits) }
