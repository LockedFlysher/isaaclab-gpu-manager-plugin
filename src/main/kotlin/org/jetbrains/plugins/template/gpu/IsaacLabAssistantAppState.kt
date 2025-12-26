package org.jetbrains.plugins.template.gpu

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "IsaacLabAssistantAppState", storages = [Storage("isaaclabAssistantApp.xml")])
class IsaacLabAssistantAppState : PersistentStateComponent<IsaacLabAssistantAppState.State> {

    data class Kv(var key: String = "", var value: String = "")

    data class RunnerPreset(
        var name: String = "",
        var entryScript: String = "",
        var task: String = "",
        var numEnvs: String = "",
        var headless: Boolean = true,
        var livestream: Boolean = false,
        var resume: Boolean = false,
        var experimentName: String = "",
        var loadRun: String = "",
        var checkpoint: String = "",
        var selectedGpus: MutableList<Int> = mutableListOf(),
        var additionalParams: MutableList<Kv> = mutableListOf(),
        var additionalEnv: MutableList<Kv> = mutableListOf(),
    )

    data class State(
        var runnerPresets: MutableList<RunnerPreset> = mutableListOf(),
        var lastRunnerPreset: String = "",
    )

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    companion object { fun getInstance(): IsaacLabAssistantAppState = service() }
}

