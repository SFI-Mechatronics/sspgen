package no.ntnu.ihb.sspgen.dsl

import no.ntnu.ihb.fmi4j.modeldescription.ModelDescription
import no.ntnu.ihb.fmi4j.modeldescription.ModelDescriptionParser
import no.ntnu.ihb.fmi4j.modeldescription.util.FmiModelDescriptionUtil
import no.ntnu.ihb.sspgen.dsl.annotations.Scoped
import no.ntnu.ihb.sspgen.dsl.extensions.getSourceFileName
import no.ntnu.ihb.sspgen.dsl.extensions.typeName
import no.ntnu.ihb.sspgen.dsl.resources.FileResource
import no.ntnu.ihb.sspgen.dsl.resources.PythonfmuResource
import no.ntnu.ihb.sspgen.dsl.resources.Resource
import no.ntnu.ihb.sspgen.dsl.resources.UrlResource
import no.ntnu.ihb.sspgen.osp.OspModelDescriptionType
import no.ntnu.ihb.sspgen.ssp.SystemStructureDescription
import no.ntnu.ihb.sspgen.sspgen
import java.io.*
import java.net.URL
import java.net.URLClassLoader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.bind.JAXB


fun ssp(archiveName: String, ctx: SspContext.() -> Unit): SspContext {
    return SspContext(archiveName).apply(ctx)
}

@Scoped
class SspContext(
    val archiveName: String
) {

    private val ssd: SystemStructureDescription = SystemStructureDescription()
    private val resources = mutableListOf<Resource>()
    private val namespaces = mutableListOf<String>()
    private var validated = false

    private val modelDescriptions: Map<String, String> by lazy {
        retrieveModelDescriptions()
    }

    internal val parsedModelDescriptions: Map<String, ModelDescription> by lazy {
        modelDescriptions.mapValues { ModelDescriptionParser.parse(it.value) }
    }

    internal val ospModelDescriptions by lazy {
        retrieveOspModelDescriptions()
    }

    fun ssd(name: String, ctx: SsdContext.() -> Unit) {
        ssd.name = name
        ssd.version = "1.0"
        ssd.generationTool = "sspgen ${sspgen.version}"
        SsdContext(ssd, { parsedModelDescriptions }, { ospModelDescriptions }).apply(ctx)
    }

    fun namespaces(ctx: NamespaceContext.() -> Unit) {
        NamespaceContext(namespaces).apply(ctx)
    }

    fun resources(ctx: ResourcesContext.() -> Unit) {
        ResourcesContext(resources).apply(ctx)
    }

    @Scoped
    inner class NamespaceContext(
        private val namespaces: MutableList<String>
    ) {

        fun namespace(namespace: String, uri: String) {
            namespaces.add("xmlns:$namespace=\"$uri\"")
        }

    }

    @Scoped
    inner class ResourcesContext(
        private val resources: MutableList<Resource>
    ) {

        fun file(filePath: String) {
            val file = File(filePath)
            if (!file.exists()) throw NoSuchFileException(file)
            FileResource(file).also { resource ->
                resources.add(resource)
            }
        }

        fun url(urlString: String) {
            UrlResource(URL(urlString)).also { resource ->
                resources.add(resource)
            }
        }

        fun pythonfmu(source: String, vararg projectFiles: String) {
            val sourceFile = File(source)
            if (!sourceFile.exists()) throw NoSuchFileException(sourceFile)
            PythonfmuResource(sourceFile, projectFiles.map { File(it) }).also { resource ->
                resources.add(resource)
            }
        }

    }

    fun ssdXml(): String {

        var xml = ByteArrayOutputStream().use { baos ->
            JAXB.marshal(ssd, baos)
            baos.toString()
        }

        xml = xml.replace("ns2:", "ssd:")
        xml = xml.replace("ns3:", "ssc:")
        xml = xml.replace("ns4:", "ssv:")
        xml = xml.replace("xmlns:ns2", "xmlns:ssd")
        xml = xml.replace("xmlns:ns3", "xmlns:ssc")
        xml = xml.replace("xmlns:ns4", "xmlns:ssv")
        xml = xml.replace("xmlns:ns5", "xmlns:ssb")

        xml = xml.replace("<any>", "")
        xml = xml.replace("</any>", "")
        xml = xml.replace("&lt;", "<")
        xml = xml.replace("/&gt;", "/>")
        xml = xml.replace("&gt;", ">")

        if (namespaces.isNotEmpty()) {
            val indexOf = xml.indexOf("xmlns")
            namespaces.forEach { namespace ->
                xml = xml.substring(0, indexOf) + "$namespace " + xml.substring(indexOf)
            }
        }

        return xml
    }

    private fun retrieveModelDescriptions(): Map<String, String> {

        if (resources.isEmpty()) {
            throw IllegalStateException("No resources has been defined. Resources must be defined prior to ssd!")
        }

        return resources.filter { it.name.endsWith(".fmu") }.associate {
            val xml = FmiModelDescriptionUtil.extractModelDescriptionXml(it.openStream())
            it.name to xml
        }

    }

    private fun extractOspModelDescription(`is`: InputStream): OspModelDescriptionType? {
        ZipInputStream(`is`.buffered()).use { zis ->
            var zipEntry: ZipEntry? = zis.nextEntry
            while (zipEntry != null) {
                if (zipEntry.name.endsWith("OspModelDescription.xml")) {
                    val xml = InputStreamReader(zis).buffered().useLines {
                        it.joinToString("\n")
                    }
                    return JAXB.unmarshal(StringReader(xml), OspModelDescriptionType::class.java)
                }
                zis.closeEntry()
                zipEntry = zis.nextEntry
            }
        }
        return null
    }

    private fun retrieveOspModelDescriptions(): Map<String, OspModelDescriptionType> {
        val ospModelDescriptions = mutableMapOf<String, OspModelDescriptionType>()
        resources.forEach { resource ->
            if (resource.name.endsWith(".fmu")) {
                extractOspModelDescription(resource.openStream())?.also { ospMd ->
                    ospModelDescriptions[resource.name] = ospMd
                }
            }
        }
        return ospModelDescriptions
    }

    private fun vdmCheck(vdmJar: File?) {
        if (vdmJar != null && vdmJar.exists() && vdmJar.extension == "jar") {

            println("VDMCheck found..")
            val cl = URLClassLoader(arrayOf(vdmJar.toURI().toURL()))
            val vdm = cl.loadClass("VDMCheck")

            val vdmMethod = vdm.getDeclaredMethod(
                "run",
                String::class.java, String::class.java, String::class.java, String::class.java
            )
            resources.filter { it.name.endsWith(".fmu") }.forEach { resource ->
                val name = resource.name
                println("Checking modelDescription of $name using VDMCheck..")
                val xml = FmiModelDescriptionUtil.extractModelDescriptionXml(resource.openStream())
                val version = FmiModelDescriptionUtil.extractVersion(xml)
                if (version.startsWith("2.")) {
                    val xml1 = if (xml.startsWith("<?xml version")) {
                        xml.split("\n").drop(1).joinToString("\n")
                    } else {
                        xml
                    }
                    vdmMethod.isAccessible = true
                    vdmMethod.invoke(null, null, xml1, null, "schema/fmi2ModelDescription.xsd")
                } else {
                    System.err.println("Unable to check FMU adhering to version $version of the FMI standard..")
                }
            }
            println("VDMCheck finished..")
        }

    }

    fun validate(vdmJar: File? = null) = apply {

        vdmCheck(vdmJar)

        ssd.system?.elements?.component?.forEach { component ->

            val fmuName = component.getSourceFileName()
            val md = parsedModelDescriptions[fmuName]
                ?: throw IllegalStateException("No modelDescription affiliated with $fmuName!")
            val mv = md.modelVariables

            component.connectors.connector.forEach { connector ->
                val name = connector.name
                when (connector.typeName()) {
                    "Integer" -> mv.integers.find { it.name == name }
                        ?: throw IllegalStateException("No Integer variable named $name found within the modelDescription of FMU named $fmuName!")
                    "Real" -> mv.reals.find { it.name == name }
                        ?: throw IllegalStateException("No Real variable named $name found within the modelDescription of FMU named $fmuName!")
                    "Boolean" -> mv.booleans.find { it.name == name }
                        ?: throw IllegalStateException("No Boolean variable named $name found within the modelDescription of FMU named $fmuName!")
                    "String" -> mv.strings.find { it.name == name }
                        ?: throw IllegalStateException("No String variable named $name found within the modelDescription of FMU named $fmuName!")
                    "Enumeration" -> mv.enumerations.find { it.name == name }
                        ?: throw IllegalStateException("No Enumeration variable named $name found within the modelDescription of FMU named $fmuName!")
                }
            }

        }

        validated = true

    }

    fun build(outputDir: File? = null, vdmJar: File? = null) {

        if (!validated) validate(vdmJar)

        val fileName = if (archiveName.endsWith(".ssp")) {
            archiveName
        } else {
            "$archiveName.ssp"
        }

        val sspArchive = if (outputDir == null) {
            File(fileName)
        } else {
            outputDir.mkdirs()
            File(outputDir, fileName)
        }

        ZipOutputStream(FileOutputStream(sspArchive).buffered()).use { zos ->

            zos.putNextEntry(ZipEntry("SystemStructure.ssd"))
            zos.write(ssdXml().toByteArray())
            zos.closeEntry()

            if (resources.isNotEmpty()) {
                zos.putNextEntry(ZipEntry(SOURCE_PREFIX))
                resources.forEach {
                    zos.putNextEntry(ZipEntry("$SOURCE_PREFIX${it.name}"))
                    zos.write(it.readBytes())
                    zos.closeEntry()
                }
                zos.closeEntry()
            }

        }

    }

    private companion object {
        const val SOURCE_PREFIX = "resources/"
    }

}
