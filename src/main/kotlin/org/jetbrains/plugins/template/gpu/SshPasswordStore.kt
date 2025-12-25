package org.jetbrains.plugins.template.gpu

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

object SshPasswordStore {

    private fun key(host: String, port: Int, user: String?): String {
        val u = (user ?: "").trim()
        return if (u.isEmpty()) "$host:$port" else "$u@$host:$port"
    }

    fun load(host: String, port: Int, user: String?): String? {
        val attrs = CredentialAttributes(generateServiceName("isaaclab-assistant.ssh", key(host, port, user)))
        return PasswordSafe.instance.getPassword(attrs)
    }

    fun save(host: String, port: Int, user: String?, password: String?) {
        val attrs = CredentialAttributes(generateServiceName("isaaclab-assistant.ssh", key(host, port, user)))
        PasswordSafe.instance.setPassword(attrs, password)
    }
}

