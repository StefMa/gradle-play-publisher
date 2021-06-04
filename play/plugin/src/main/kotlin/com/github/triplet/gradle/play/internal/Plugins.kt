package com.github.triplet.gradle.play.internal

import com.android.build.api.variant.ApplicationVariant
import com.github.triplet.gradle.common.utils.PLUGIN_GROUP
import com.github.triplet.gradle.common.utils.nullOrFull
import com.github.triplet.gradle.play.PlayPublisherExtension
import com.github.triplet.gradle.play.tasks.CommitEdit
import com.github.triplet.gradle.play.tasks.internal.PlayApiService
import org.gradle.api.InvalidUserDataException
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.register
import java.util.UUID

internal val ApplicationVariant.flavorNameOrDefault
    get() = flavorName.nullOrFull() ?: "main"

internal val ApplicationVariant.playPath get() = "$RESOURCES_OUTPUT_PATH/$name/$PLAY_PATH"

internal inline fun <reified T : Task> Project.newTask(
        name: String,
        description: String? = null,
        constructorArgs: Array<Any> = emptyArray(),
        allowExisting: Boolean = false,
        noinline block: T.() -> Unit = {},
): TaskProvider<T> {
    val config: T.() -> Unit = {
        this.description = description
        this.group = PLUGIN_GROUP.takeUnless { description.isNullOrBlank() }
        block()
    }

    return try {
        tasks.register<T>(name, *constructorArgs).apply { configure(config) }
    } catch (e: InvalidUserDataException) {
        if (allowExisting) {
            @Suppress("UNCHECKED_CAST")
            tasks.named(name) as TaskProvider<T>
        } else {
            throw e
        }
    }
}

internal fun Project.getCommitEditTask(
        appId: String,
        extension: PlayPublisherExtension,
        api: Provider<PlayApiService>,
): TaskProvider<CommitEdit> {
    val taskName = "commitEditFor" + appId.split(".").joinToString("Dot") { it.capitalize() }
    return rootProject.newTask(taskName, allowExisting = true, constructorArgs = arrayOf(extension)) {
        apiService.set(api)
    }
}

internal fun ApplicationVariant.buildExtension(
        project: Project,
        extensionContainer: NamedDomainObjectContainer<PlayPublisherExtension>,
        baseExtension: PlayPublisherExtension,
        cliOptionsExtension: PlayPublisherExtension,
): PlayPublisherExtension = buildExtensionInternal(
        project,
        this,
        extensionContainer,
        baseExtension,
        cliOptionsExtension
)

private fun buildExtensionInternal(
        project: Project,
        variant: ApplicationVariant,
        extensionContainer: NamedDomainObjectContainer<PlayPublisherExtension>,
        baseExtension: PlayPublisherExtension,
        cliOptionsExtension: PlayPublisherExtension,
): PlayPublisherExtension {
    val variantExtension = extensionContainer.findByName(variant.name)
    val flavorExtension = variant.productFlavors.mapNotNull { (_, flavor) ->
        extensionContainer.findByName(flavor)
    }.singleOrNull()
    val dimensionExtension = variant.productFlavors.mapNotNull { (dimension, _) ->
        extensionContainer.findByName(dimension)
    }.singleOrNull()
    val buildTypeExtension = variant.buildType?.let { extensionContainer.findByName(it) }

    val rawExtensions = listOf(
            cliOptionsExtension,
            variantExtension,
            flavorExtension,
            dimensionExtension,
            buildTypeExtension,
            baseExtension
    )
    val extensions = rawExtensions.filterNotNull().distinctBy {
        it.name
    }.map {
        val priority = rawExtensions.subList(1, rawExtensions.size).indexOfFirst { it != null }
        ExtensionMergeHolder(
                original = it,
                uninitializedCopy = project.objects.newInstance("$priority:${UUID.randomUUID()}")
        )
    }

    return mergeExtensions(extensions)
}

internal fun PlayPublisherExtension.toPriority() = name.split(":").first().toInt()
