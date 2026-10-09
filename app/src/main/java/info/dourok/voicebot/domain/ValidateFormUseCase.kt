package info.dourok.voicebot.domain

import info.dourok.voicebot.data.model.ServerFormData
import info.dourok.voicebot.data.model.ServerType
import info.dourok.voicebot.data.model.ValidationResult
import javax.inject.Inject

// :feature:form/domain/ValidateFormUseCase.kt
class ValidateFormUseCase @Inject constructor() {
    operator fun invoke(formData: ServerFormData): ValidationResult {
        val errors = mutableMapOf<String, String>()

        when (formData.serverType) {
            ServerType.XiaoZhi -> {
                if (formData.xiaoZhiConfig.webSocketUrl.isEmpty()) {
                    errors["xiaoZhiWebSocketUrl"] = "WebSocket URL 不能为空"
                }
                if (formData.xiaoZhiConfig.qtaUrl.isEmpty()) {
                    errors["qtaUrl"] = "QTA URL 不能为空"
                }
            }
            ServerType.SelfHost -> {
                if (formData.selfHostConfig.webSocketUrl.isEmpty()) {
                    errors["selfHostWebSocketUrl"] = "WebSocket URL 不能为空"
                }
            }
        }

        return ValidationResult(errors.isEmpty(), errors)
    }
}