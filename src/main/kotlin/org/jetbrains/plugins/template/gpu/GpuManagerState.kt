package org.jetbrains.plugins.template.gpu

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.components.Service
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

@Service(Service.Level.APP)
@State(name = "GpuManagerState", storages = [Storage("gpuManager.xml")])
class GpuManagerState : PersistentStateComponent<GpuManagerState.State> {

    data class State(
        var localMode: Boolean = true,
        var host: String? = null,
        var port: Int = 22,
        var username: String? = null,
        var identity: String? = null,
        var usePassword: Boolean = false,
        var rememberPassword: Boolean = false,
        var intervalSec: Double = 5.0,
    )

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    fun savePassword(host: String, port: Int, user: String?, password: String?) {
        val key = passwordKey(host, port, user)
        val attrs = CredentialAttributes(generateServiceName("isaaclab-gpu-manager", key))
        PasswordSafe.instance.setPassword(attrs, password)
    }

    fun loadPassword(host: String, port: Int, user: String?): String? {
        val key = passwordKey(host, port, user)
        val attrs = CredentialAttributes(generateServiceName("isaaclab-gpu-manager", key))
        return PasswordSafe.instance.getPassword(attrs)
    }

    private fun passwordKey(host: String, port: Int, user: String?): String {
        val u = (user ?: "").trim()
        return if (u.isEmpty()) "$host:$port" else "$u@$host:$port"
    }

    companion object { fun getInstance(): GpuManagerState = service() }
}
