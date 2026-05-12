package io.cloudshiftdev.mavensync

import com.google.common.xml.XmlEscapers

internal data class MavenMetadataXml(
    val group: String,
    val artifactId: String,
    val versions: List<String>,
    val lastUpdated: String,
    val latest: String,
    val release: String,
) {
    fun toXml(): String {
        val escaper = XmlEscapers.xmlContentEscaper()
        return buildString(1024 + (100 * versions.size)) {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<metadata>\n")
            append("  <groupId>${escaper.escape(group)}</groupId>\n")
            append("  <artifactId>${escaper.escape(artifactId)}</artifactId>\n")
            append("  <versioning>\n")
            append("    <latest>${escaper.escape(latest)}</latest>\n")
            append("    <release>${escaper.escape(release)}</release>\n")
            append("    <versions>\n")
            versions.forEach { version ->
                append("      <version>${escaper.escape(version)}</version>\n")
            }
            append("    </versions>\n")
            append("    <lastUpdated>${escaper.escape(lastUpdated)}</lastUpdated>\n")
            append("  </versioning>\n")
            append("</metadata>\n")
        }
    }
}
