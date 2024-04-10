package org.usvm

import io.ksmt.expr.KBitVec32Value
import kotlinx.coroutines.runBlocking
import org.jacodb.api.JcClassOrInterface
import org.jacodb.api.JcClasspath
import org.jacodb.api.JcMethod
import org.jacodb.api.JcType
import org.jacodb.api.cfg.JcInst
import org.jacodb.api.ext.findClass
import org.jacodb.api.ext.int
import org.jacodb.api.ext.methods
import org.jacodb.impl.jacodb
import org.junit.jupiter.api.Test
import org.usvm.api.targets.JcTarget
import org.usvm.constraints.UPathConstraints
import org.usvm.instrumentation.executor.UTestConcreteExecutor
import org.usvm.instrumentation.instrumentation.JcRuntimeTraceInstrumenterFactory
import org.usvm.instrumentation.testcase.UTest
import org.usvm.instrumentation.testcase.api.UTestAllocateMemoryCall
import org.usvm.instrumentation.testcase.api.UTestExecutionSuccessResult
import org.usvm.instrumentation.testcase.api.UTestIntExpression
import org.usvm.instrumentation.testcase.api.UTestMethodCall
import org.usvm.instrumentation.util.InstrumentationModuleConstants
import org.usvm.machine.JcExprTranslator
import org.usvm.machine.JcMachine
import org.usvm.machine.JcMethodEntrypointInst
import org.usvm.machine.state.JcState
import org.usvm.solver.USatResult
import org.usvm.solver.USolverBase
import java.io.File

class Test {

    private val buildDir = "/home/ebukreev/IdeaProjects/usvm/build"

    class ConcretePathTarget(location: JcInst) : JcTarget(location)

    @Test
    fun test(): Unit = runBlocking {
        val cp = jacodb { useProcessJavaRuntime() }
            .classpath(listOf(File(buildDir)))

        val clazz = cp.findClass("Main")
        val method = clazz.methods.first { it.name == "foo" }

        val concreteExecutor = UTestConcreteExecutor(
            JcRuntimeTraceInstrumenterFactory::class,
            buildDir, cp, InstrumentationModuleConstants.methodExecutionTimeout
        )

        val concreteResult = concreteExecutor.execute(1, cp, clazz, method)

        println()
        println("Concrete result trace:")
        println(concreteResult.trace?.joinToString(separator = "\n"))
        println()

        val target = getSymbolicTargetFromConcreteTrace(concreteResult.trace!!)
        val symbolicResult = executeSymbolic(method, target, cp)

        println("Symbolic result:")
        println(symbolicResult.pathNode.allStatements.reversed().joinToString(separator = "\n"))
        println()

        val solver =  symbolicResult.ctx.solver<JcType>()
        val negatedLastConstraint = negateConstraint(symbolicResult.pathConstraints, 2, symbolicResult.ctx)
        val concreteArgForNegatedPath = computeConcreteArgByConstraints(solver, negatedLastConstraint, symbolicResult)

        val anotherConcreteResult = concreteExecutor.execute(concreteArgForNegatedPath, cp, clazz, method)

        println("Another concrete result:")
        println(anotherConcreteResult.trace?.joinToString(separator = "\n"))
        println()

        concreteExecutor.close()
    }

    private suspend fun UTestConcreteExecutor.execute(
        input: Int,
        cp: JcClasspath,
        clazz: JcClassOrInterface,
        method: JcMethod
    ): UTestExecutionSuccessResult {
        ensureRunnerAlive()
        return executeAsync(
            UTest(
                emptyList(), UTestMethodCall(
                    UTestAllocateMemoryCall(clazz), method, listOf(UTestIntExpression(input, cp.int))
                )
            )
        ) as UTestExecutionSuccessResult
    }

    private fun executeSymbolic(method: JcMethod, target: ConcretePathTarget, cp: JcClasspath): JcState {
        val options = UMachineOptions(
            pathSelectionStrategies = listOf(PathSelectionStrategy.TARGETED),
            stopOnTargetsReached = true
        )

        return JcMachine(cp, options).analyze(method, listOf(target)).first()
    }

    private fun getSymbolicTargetFromConcreteTrace(trace: List<JcInst>): ConcretePathTarget {
        // TODO
        return trace.fold(null) { acc: ConcretePathTarget?, jcInst ->
            ConcretePathTarget(jcInst).also { if (acc != null) it.addChild(acc) } }!!
    }

    private fun negateConstraint(
        constraints: UPathConstraints<JcType>,
        indexToNegate: Int,
        ctx: UContext<UBv32Sort>
    ): UPathConstraints<JcType> {
        val translatedConstraints = constraints.constraints(JcExprTranslator(ctx))

        val result = UPathConstraints<JcType>(ctx)
        translatedConstraints.withIndex().forEach { (index, value) ->
            result += if (index == indexToNegate) with(ctx) { value.not() } else value
        }

        return result
    }

    private fun computeConcreteArgByConstraints(
        solver: USolverBase<JcType>,
        constraints: UPathConstraints<JcType>,
        symbolicResult: JcState
    ): Int {
        (symbolicResult.pathNode.allStatements.last() as JcMethodEntrypointInst)
            .entrypointArguments.first().second // TODO: why only one?

        return ((solver.check(constraints) as USatResult)
            .model.stack.readRegister(1, symbolicResult.ctx.integerSort) as KBitVec32Value).intValue
    }
}