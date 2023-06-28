/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.dce

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.backend.js.lower.PrimaryConstructorLowering
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import java.io.File

internal fun IrDeclaration.fqNameForDceDump(): String {
    // TODO: sanitize names
    val fqn = (this as? IrDeclarationWithName)?.fqNameWhenAvailable?.asString() ?: "<unknown>"
    val signature = when (this is IrFunction) {
        true -> this.valueParameters.joinToString(prefix = "(", postfix = ")") { it.type.dumpKotlinLike() }
        else -> ""
    }
    val synthetic = when (this.origin == PrimaryConstructorLowering.SYNTHETIC_PRIMARY_CONSTRUCTOR) {
        true -> "[synthetic]"
        else -> ""
    }

    return (fqn + signature + synthetic)
}

fun dumpDeclarationIrSizesIfNeed(path: String?, allModules: List<IrModuleFragment>) {
    if (path == null) return

    val declarations = linkedSetOf<IrDeclaration>()

    allModules.forEach {
        it.acceptChildrenVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitDeclaration(declaration: IrDeclarationBase) {
                when (declaration) {
                    is IrFunction,
                    is IrProperty,
                    is IrField,
                    is IrAnonymousInitializer,
                    is IrClass,
                    -> {
                        declarations.add(declaration)
                    }
                }

                super.visitDeclaration(declaration)
            }
        })
    }

    val out = File(path)
    val (prefix, postfix, separator, indent) = when (out.extension) {
        "json" -> listOf("{\n", "\n}", ",\n", "    ")
        "js" -> listOf("const kotlinDeclarationsSize = {\n", "\n};\n", ",\n", "    ")
        else -> listOf("", "", "\n", "")
    }

    val value = declarations
        .groupBy({ it.fqNameForDceDump() }, { it })
        .map { (k, v) -> k to v.maxBy { it.dumpKotlinLike().length } }
        .joinToString(separator, prefix, postfix) { (fqn, element) ->
            val size = element.dumpKotlinLike().length
            val type = when (element) {
                is IrFunction -> "function"
                is IrProperty -> "property"
                is IrField -> "field"
                is IrAnonymousInitializer -> "anonymousInitializer"
                is IrClass -> "class"
                else -> "unknown"
            }
            """$indent"${fqn.removeQuotes()}": {
                |$indent$indent"size": $size,
                |$indent$indent"type": "$type"
                |$indent}
            """.trimMargin()
        }

    out.writeText(value)
}

internal fun String.removeQuotes() = replace('"'.toString(), "")
    .replace("'", "")
    .replace("\\", "\\\\")
