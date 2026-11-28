package org.koitharu.kotatsu.legado

import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Parser
import org.mozilla.javascript.ast.*

/**
 * Experimental pure-expression subset, NOT a general JavaScript runtime.
 * Validate the AST before execution; no loops, assignments, object/array construction,
 * arbitrary properties, native objects, network, eval, or user-defined functions.
 */
internal class LegadoPureExpression private constructor(private val expression: String) {
    fun evaluate(result: String, variables: LegadoVariables): String {
        require(result.length <= MAX_INPUT) { "Expression input exceeds limit" }
        val factory = object : ContextFactory() {
            private var instructions = 0
            override fun makeContext(): Context = super.makeContext().apply {
                optimizationLevel = -1
                instructionObserverThreshold = 1000
                setClassShutter { false }
            }
            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                instructions += instructionCount
                if (instructions > 100000 || Thread.currentThread().isInterrupted) {
                    throw IllegalStateException("Expression instruction limit exceeded")
                }
            }
        }
        return factory.call { cx ->
            val scope = cx.initSafeStandardObjects()
            // Only inert strings cross into the interpreter.
            org.mozilla.javascript.ScriptableObject.putProperty(scope, "result", result)
            val data = kotlinx.serialization.json.JsonObject(
                variables.snapshot().mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }
            ).toString()
            val quoted = kotlinx.serialization.json.JsonPrimitive(data).toString()
            cx.evaluateString(scope,
                "var java=(function(v){return {get:function(k){return Object.prototype.hasOwnProperty.call(v,k)?v[k]:'';}}})(JSON.parse(" + quoted + "));",
                "legado-bridge", 1, null)
            val value = cx.evaluateString(scope, "(" + expression + ")", "legado-expression", 1, null)
            require(value == null || value is CharSequence || value is Number || value is Boolean ||
                org.mozilla.javascript.Undefined.isUndefined(value)) { "Expression must return a scalar" }
            val output = if (value == null || org.mozilla.javascript.Undefined.isUndefined(value)) "" else Context.toString(value)
            require(output.length <= MAX_INPUT) { "Expression result exceeds limit" }
            output
        }
    }

    companion object {
        private const val MAX_INPUT = 65536
        private val stringMethods = setOf("trim", "toLowerCase", "toUpperCase", "substring", "slice", "charAt", "indexOf", "lastIndexOf", "replace")
        private val binaryOperators = setOf(
            org.mozilla.javascript.Token.ADD, org.mozilla.javascript.Token.SUB,
            org.mozilla.javascript.Token.MUL, org.mozilla.javascript.Token.DIV, org.mozilla.javascript.Token.MOD,
            org.mozilla.javascript.Token.EQ, org.mozilla.javascript.Token.NE,
            org.mozilla.javascript.Token.SHEQ, org.mozilla.javascript.Token.SHNE,
            org.mozilla.javascript.Token.LT, org.mozilla.javascript.Token.LE,
            org.mozilla.javascript.Token.GT, org.mozilla.javascript.Token.GE,
            org.mozilla.javascript.Token.AND, org.mozilla.javascript.Token.OR,
        )

        fun parse(expression: String): LegadoPureExpression {
            val code = expression.trim().removeSuffix(";").trim()
            require(code.length in 1..1024)
            val ast = Parser().parse("($code)", "legado-expression", 1)
            var count = 0
            ast.visit { node ->
                require(++count <= 64) { "Expression too complex" }
                when (node) {
                    is AstRoot, is ExpressionStatement, is ParenthesizedExpression,
                    is ConditionalExpression, is StringLiteral, is NumberLiteral -> Unit
                    is KeywordLiteral -> require(node.type in setOf(org.mozilla.javascript.Token.TRUE,
                        org.mozilla.javascript.Token.FALSE, org.mozilla.javascript.Token.NULL))
                    is Name -> {
                        val parent = node.parent
                        if (parent is PropertyGet && parent.property === node) {
                            require(node.identifier in stringMethods || node.identifier == "get" || node.identifier == "length")
                        } else require(node.identifier in setOf("result", "java"))
                    }
                    is PropertyGet -> {
                        val property = node.property.identifier
                        val target = node.target
                        if (property == "length") {
                            require(target is Name && target.identifier == "result")
                        } else {
                            require(node.parent is FunctionCall && (node.parent as FunctionCall).target === node)
                            require(target is Name)
                            require((target.identifier == "result" && property in stringMethods) ||
                                (target.identifier == "java" && property == "get"))
                        }
                    }
                    is FunctionCall -> {
                        val target = node.target as? PropertyGet ?: error("Unsupported function")
                        require(node.arguments.size <= 2)
                        require(node.arguments.all { it is StringLiteral || it is NumberLiteral }) { "Only literal method arguments supported" }
                        if (target.property.identifier == "replace") {
                            require(node.arguments.size == 2 && node.arguments.all { it is StringLiteral })
                        }
                        if (target.property.identifier == "get") {
                            require(node.arguments.size == 1 && node.arguments[0] is StringLiteral &&
                                LegadoVariables.validKey((node.arguments[0] as StringLiteral).value))
                        }
                    }
                    is InfixExpression -> require(node.operator in binaryOperators)
                    is UnaryExpression -> require(node.operator in setOf(org.mozilla.javascript.Token.NOT,
                        org.mozilla.javascript.Token.NEG, org.mozilla.javascript.Token.POS))
                    else -> throw IllegalArgumentException("Unsupported expression: " + node.javaClass.simpleName)
                }
                true
            }
            return LegadoPureExpression(code)
        }
    }
}
