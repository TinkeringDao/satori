package com.satori.regex

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * The internal and private code of the app's other modules, which the app's tests can't name: a
 * Kotlin object's functions, a file's top-level ones, a static field. On the JVM an internal member
 * function's name carries its module's after a "$" ("withoutHesitations$domain").
 */
internal object Internals {

    /** The function [name] of the Kotlin object [className], called with [args]. */
    fun call(className: String, name: String, vararg args: Any?): Any? {
        val type = Class.forName(className)
        return invoke(type, type.getField("INSTANCE").get(null), name, args)
    }

    /** The top-level function [name] of the file class [className] ("...Kt"), called with [args]. */
    fun callStatic(className: String, name: String, vararg args: Any?): Any? = invoke(Class.forName(className), null, name, args)

    /** The function [name] of [target], called with [args]. */
    fun callOn(target: Any, name: String, vararg args: Any?): Any? = invoke(target.javaClass, target, name, args)

    /** The value of the static field [name] of [className]: a private one, a companion's, a file's. */
    fun field(className: String, name: String): Any? =
        Class.forName(className).getDeclaredField(name).apply { isAccessible = true }.get(null)

    /** A new [className] made with the constructor that takes [args]. */
    fun create(className: String, vararg args: Any?): Any {
        val constructor = Class.forName(className).declaredConstructors.single { constructor ->
            !constructor.isSynthetic && constructor.parameterCount == args.size && takes(constructor.parameterTypes, args)
        }
        constructor.isAccessible = true
        return try {
            constructor.newInstance(*args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun invoke(type: Class<*>, target: Any?, name: String, args: Array<out Any?>): Any? {
        val method = type.declaredMethods.filter { it.isNamed(name) && it.parameterCount == args.size && takes(it.parameterTypes, args) }
            .singleOrNull() ?: error("No single function $name of ${type.name} for these ${args.size} arguments")
        method.isAccessible = true
        return try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            // What the function threw: an ExceptionInInitializerError of a class it started, say.
            throw e.targetException
        }
    }

    private fun Method.isNamed(name: String): Boolean = !isSynthetic && (this.name == name || this.name.startsWith("$name$"))

    private fun takes(types: Array<Class<*>>, args: Array<out Any?>): Boolean =
        types.zip(args).all { (type, arg) -> arg == null || boxed(type).isInstance(arg) }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Double::class.javaPrimitiveType -> Double::class.javaObjectType
        else -> type
    }
}
