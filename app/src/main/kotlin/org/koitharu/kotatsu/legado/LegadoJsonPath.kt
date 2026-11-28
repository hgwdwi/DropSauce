package org.koitharu.kotatsu.legado

import kotlinx.serialization.json.*

/** Bounded JSONPath subset: properties, wildcards, indexes, unions, slices and recursive names. */
internal class LegadoJsonPath private constructor(private val steps: List<Step>) {
    private sealed interface Step {
        data class Property(val name: String) : Step
        data class Recursive(val name: String) : Step
        data class Indices(val indices: List<Int>) : Step
        data class Slice(val start: Int?, val end: Int?, val step: Int) : Step
        data object Wildcard : Step
    }

    fun select(root: Any): List<JsonElement> {
        var current = listOfNotNull(root as? JsonElement)
        for (step in steps) {
            val out = ArrayList<JsonElement>()
            fun add(value: JsonElement?) {
                if (value != null && value !is JsonNull) {
                    require(out.size < LIMIT) { "JSONPath result limit exceeded" }
                    out.add(value)
                }
            }
            for (node in current) {
                when (step) {
                    is Step.Property -> add((node as? JsonObject)?.get(step.name))
                    is Step.Indices -> if (node is JsonArray) {
                        for (index in step.indices) add(node.getOrNull(if (index < 0) node.size + index else index))
                    }
                    is Step.Slice -> if (node is JsonArray) {
                        fun bound(index: Int?, default: Int): Int =
                            (index?.let { if (it < 0) node.size.toLong() + it else it.toLong() } ?: default.toLong())
                                .coerceIn(0L, node.size.toLong()).toInt()
                        val start = bound(step.start, 0)
                        val end = bound(step.end, node.size)
                        for (i in start until end step step.step) add(node[i])
                    }
                    Step.Wildcard -> when (node) {
                        is JsonArray -> node.forEach(::add)
                        is JsonObject -> node.values.forEach(::add)
                        else -> Unit
                    }
                    is Step.Recursive -> {
                        val pending = ArrayDeque<JsonElement>()
                        pending.add(node)
                        var visited = 0
                        while (pending.isNotEmpty()) {
                            require(++visited <= 100000) { "JSONPath recursive traversal limit exceeded" }
                            val value = pending.removeLast()
                            when (value) {
                                is JsonObject -> {
                                    add(value[step.name])
                                    value.values.toList().asReversed().forEach { pending.addLast(it) }
                                }
                                is JsonArray -> value.asReversed().forEach { pending.addLast(it) }
                                else -> Unit
                            }
                        }
                    }
                }
            }
            current = out
        }
        return current.filterNot { it is JsonNull }
    }

    companion object {
        private const val LIMIT = 10000
        private val token = Regex("""(?:\.\.([A-Za-z_][A-Za-z0-9_-]*)|\.([A-Za-z_][A-Za-z0-9_-]*)|(\.\*)|\['([^']+)'\]|\["([^"]+)"\]|\[(-?\d+(?:,-?\d+)*|\*|-?\d*:-?\d*(?::-?\d+)?)\])""")
        fun parse(path: String): LegadoJsonPath {
            require(path.startsWith("$") && path.length <= 512)
            var end = 1
            val steps = ArrayList<Step>()
            for (match in token.findAll(path, 1)) {
                require(match.range.first == end && steps.size < 32) { "Unsupported JSONPath segment" }
                end = match.range.last + 1
                val g = match.groupValues
                val step = when {
                    g[1].isNotEmpty() -> Step.Recursive(g[1])
                    g[2].isNotEmpty() -> Step.Property(g[2])
                    g[3].isNotEmpty() || g[6] == "*" -> Step.Wildcard
                    g[4].isNotEmpty() -> Step.Property(g[4])
                    g[5].isNotEmpty() -> Step.Property(g[5])
                    ':' in g[6] -> {
                        val parts = g[6].split(':')
                        val step = parts.getOrNull(2)?.toInt() ?: 1
                        require(step in 1..10000)
                        Step.Slice(parts[0].takeIf { it.isNotEmpty() }?.toInt(),
                            parts[1].takeIf { it.isNotEmpty() }?.toInt(), step)
                    }
                    else -> {
                        val indices = g[6].split(',').map { it.toInt() }
                        require(indices.size <= 100)
                        Step.Indices(indices)
                    }
                }
                steps.add(step)
            }
            require(end == path.length) { "Unsupported JSONPath segment" }
            return LegadoJsonPath(steps)
        }
    }
}
