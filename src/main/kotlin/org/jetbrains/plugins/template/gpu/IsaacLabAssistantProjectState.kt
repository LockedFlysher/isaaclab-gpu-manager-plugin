package org.jetbrains.plugins.template.gpu

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "IsaacLabAssistantProjectState", storages = [Storage("isaaclabAssistant.xml")])
class IsaacLabAssistantProjectState : PersistentStateComponent<IsaacLabAssistantProjectState.State> {

    data class Kv(var key: String = "", var value: String = "")

    data class State(
        // Runner inputs
        var entryScript: String = "",
        var task: String = "",
        var numEnvs: String = "",
        var headless: Boolean = true,
        var livestream: Boolean = false,
        var resume: Boolean = false,
        var experimentName: String = "",
        var loadRun: String = "",
        var checkpoint: String = "",

        // Runner selections
        var selectedGpus: MutableList<Int> = mutableListOf(),

        // Additional params/env tables
        var additionalParams: MutableList<Kv> = mutableListOf(),
        var additionalEnv: MutableList<Kv> = mutableListOf(),

        // Connection settings
        // - IDE_SSH: use IDE-detected SSH target (Gateway / SSH configs)
        // - MANUAL_SSH: user-provided host/port/user
        var connectionMode: String = "IDE_SSH",
        var manualSshUser: String = "",
        var manualSshHost: String = "",
        var manualSshPort: Int = 22,

        // Reverse tunnel (ssh -R): remote bind -> local port
        var reverseTunnelBindPort: Int = 7897,
        var reverseTunnelLocalPort: Int = 7897,
    )

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    companion object {
        fun getInstance(project: Project): IsaacLabAssistantProjectState = project.service()
    }
}
