package org.jetbrains.plugins.template.runner

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.icons.AllIcons

class IsaacLabRunConfigurationType : ConfigurationType {
    override fun getDisplayName(): String = "IsaacLab"
    override fun getConfigurationTypeDescription(): String = "Run IsaacLab training remotely via SSH"
    override fun getIcon() = AllIcons.RunConfigurations.Application
    override fun getId(): String = "IsaacLabRunConfiguration"

    override fun getConfigurationFactories(): Array<ConfigurationFactory> =
        arrayOf(IsaacLabRunConfigurationFactory(this))
}

