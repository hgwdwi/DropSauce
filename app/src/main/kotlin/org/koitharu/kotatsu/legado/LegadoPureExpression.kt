package org.koitharu.kotatsu.legado

import kotlinx.serialization.json.contentOrNull
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
                "var __legadoVars=Object.create(null);" +
                    "var __legadoInitial=JSON.parse(" + quoted + ");" +
                    "Object.keys(__legadoInitial).forEach(function(k){__legadoVars[k]=__legadoInitial[k];});" +
                    "var java={" +
                    "get:function(k){k=String(k);return Object.prototype.hasOwnProperty.call(__legadoVars,k)?__legadoVars[k]:'';}," +
                    "put:function(k,v){k=String(k);if(!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(k))throw new Error('Invalid variable key');" +
                    "if(!Object.prototype.hasOwnProperty.call(__legadoVars,k)&&Object.keys(__legadoVars).length>=64)throw new Error('Variable limit exceeded');" +
                    "__legadoVars[k]=String(v);return __legadoVars[k];}" +
                    "};",
                "legado-bridge", 1, null)
            val value = cx.evaluateString(scope, "(" + expression + ")", "legado-expression", 1, null)
            require(value == null || value is CharSequence || value is Number || value is Boolean ||
                org.mozilla.javascript.Undefined.isUndefined(value)) { "Expression must return a scalar" }
            val output = if (value == null || org.mozilla.javascript.Undefined.isUndefined(value)) "" else Context.toString(value)
            require(output.length <= MAX_INPUT) { "Expression result exceeds limit" }
            val updated = cx.evaluateString(scope, "JSON.stringify(__legadoVars)", "legado-state", 1, null)
            val json = kotlinx.serialization.json.Json.parseToJsonElement(Context.toString(updated)) as? kotlinx.serialization.json.JsonObject
                ?: error("Invalid Java bridge state")
            require(json.size <= 64) { "Rule variable limit exceeded" }
            for ((key, item) in json) {
                val text = (item as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    ?: error("Rule variables must remain strings")
                variables.put(key, text)
            }
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
                            require(node.identifier in stringMethods || node.identifier in setOf("get", "put", "length"))
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
                                (target.identifier == "java" && property in setOf("get", "put")))
                        }
                    }
                    is FunctionCall -> {
                        val target = node.target as? PropertyGet ?: error("Unsupported function")
                        val method = target.property.identifier
                        require(node.arguments.size <= 2)
                        when (method) {
                            "get" -> require(node.arguments.size == 1 && node.arguments[0] is StringLiteral &&
                                LegadoVariables.validKey((node.arguments[0] as StringLiteral).value))
                            "put" -> require(node.arguments.size == 2 && node.arguments[0] is StringLiteral &&
                                LegadoVariables.validKey((node.arguments[0] as StringLiteral).value))
                            "replace" -> require(node.arguments.size == 2 && node.arguments.all { it is StringLiteral })
                            else -> require(node.arguments.all { it is StringLiteral || it is NumberLiteral }) {
                                "Only literal method arguments supported"
                            }
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
