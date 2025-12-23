package org.jetbrains.plugins.template.runner

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.openapi.project.Project

class IsaacLabRunConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = "IsaacLabRunConfigurationFactory"
    override fun createTemplateConfiguration(project: Project) = IsaacLabRunConfiguration(project, this, "IsaacLab")
}

