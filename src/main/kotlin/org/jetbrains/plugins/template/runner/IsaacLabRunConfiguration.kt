package org.jetbrains.plugins.template.runner

import com.intellij.execution.Executor
import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.jetbrains.plugins.template.gpu.IsaacLabRunMode
import org.jetbrains.plugins.template.gpu.IsaacLabRunner
import org.jetbrains.plugins.template.gpu.IsaacLabRunnerSpec
import org.jetbrains.plugins.template.gpu.SshParams

class IsaacLabRunConfiguration(
    project: Project,
    factory: IsaacLabRunConfigurationFactory,
    name: String,
) : RunConfigurationBase<RunConfigurationOptions>(project, factory, name) {

    data class State(
        var host: String? = null,
        var port: Int = 22,
        var username: String? = null,
        var identityFile: String? = null,

        var mode: String = "Conda",
        var condaEnv: String? = null,
        var dockerContainer: String? = null,

        var script: String? = null,
        var task: String? = null,
        var numEnvs: Int = 1,
        var gpuList: String? = null, // "0,1,2"

        var extraParamsText: String? = null,
        var extraEnvText: String? = null,
    )

    var state: State = State()

    override fun readExternal(element: Element) {
        super.readExternal(element)
        try { XmlSerializer.deserializeInto(state, element) } catch (_: Throwable) {}
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        try { XmlSerializer.serializeInto(state, element) } catch (_: Throwable) {}
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfigurationBase<*>> =
        IsaacLabRunConfigurationEditor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        val host = state.host?.trim().orEmpty()
        if (host.isEmpty()) return null

        val mode = if (state.mode.equals("Docker", ignoreCase = true)) IsaacLabRunMode.Docker else IsaacLabRunMode.Conda
        val gpuList = parseGpuList(state.gpuList)
        val numEnvs = state.numEnvs

        val params = IsaacLabRunner.parseParams(state.extraParamsText.orEmpty()).toMutableList()
        // Migration: older versions stored the python entry in `script`; new versions use `-p=<script>` in Params.
        val legacyScript = state.script?.trim().orEmpty()
        if (legacyScript.isNotEmpty() && params.none { (k, _) -> k.trim() == "-p" }) {
            params.add(0, Pair("-p", legacyScript))
        }

        val spec = IsaacLabRunnerSpec(
            mode = mode,
            condaEnv = state.condaEnv.orEmpty(),
            dockerContainer = state.dockerContainer.orEmpty(),
            task = state.task.orEmpty(),
            numEnvs = numEnvs,
            extraParams = params,
            extraEnv = IsaacLabRunner.parseEnv(state.extraEnvText.orEmpty()),
            gpuList = gpuList,
        )
        val ssh = SshParams(
            host = host,
            port = state.port,
            username = state.username,
            identityFile = state.identityFile,
            password = null,
            timeoutSec = 60 * 60, // allow long runs; user can stop in UI
        )
        return IsaacLabRunProfileState(environment, ssh, spec)
    }

    private fun parseGpuList(text: String?): List<Int> {
        val s = text.orEmpty().trim()
        if (s.isEmpty()) return emptyList()
        return s.split(',', ' ', ';')
            .mapNotNull { it.trim().takeIf { t -> t.isNotEmpty() }?.toIntOrNull() }
            .distinct()
            .sorted()
    }

    private class IsaacLabRunProfileState(
        private val environment: ExecutionEnvironment,
        private val ssh: SshParams,
        private val spec: IsaacLabRunnerSpec,
    ) : RunProfileState {
        override fun execute(executor: Executor, runner: com.intellij.execution.runners.ProgramRunner<*>): ExecutionResult? {
            val handler = IsaacLabRemoteProcessHandler(ssh, spec)
            val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(environment.project).console
            console.attachToProcess(handler)
            return com.intellij.execution.DefaultExecutionResult(console, handler)
        }
    }
}
