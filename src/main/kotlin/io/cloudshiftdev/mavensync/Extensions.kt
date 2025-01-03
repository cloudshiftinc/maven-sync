package io.cloudshiftdev.mavensync

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*

internal val Url.isDirectory: Boolean
    get() = encodedPath.endsWith("/")

internal val Url.filename: Filename
    get() = Filename(encodedPath.substringAfterLast("/"))

internal val Group.pathSegments: List<String>
    get() = value.split('.')
//
// internal fun url(coordinates: Coordinates, assetName: Filename? = null): Url {
//    val builder =
//        URLBuilder(repoUrl)
//            .appendPathSegments(coordinates.group.pathSegments)
//            .appendPathSegments(coordinates.artifact.value, coordinates.artifactVersion.value)
//    if (assetName != null) builder.appendPathSegments(assetName.value)
//    else builder.appendPathSegments("")
//    return builder.build()
// }
//
// internal fun metadataUrl(group: Group, artifact: Artifact) =
//    URLBuilder(repoUrl)
//        .appendPathSegments(group.pathSegments)
//        .appendPathSegments(artifact.value, "maven-metadata.xml")
//        .build()
