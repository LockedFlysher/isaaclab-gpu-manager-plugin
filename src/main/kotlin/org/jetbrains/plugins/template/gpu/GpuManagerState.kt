package org.jetbrains.plugins.template.gpu

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.components.Service

@Service(Service.Level.APP)
@State(name = "GpuManagerState", storages = [Storage("gpuManager.xml")])
class GpuManagerState : PersistentStateComponent<GpuManagerState.State> {

    data class State(
        var intervalSec: Double = 5.0,
    )

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    companion object { fun getInstance(): GpuManagerState = service() }
}
