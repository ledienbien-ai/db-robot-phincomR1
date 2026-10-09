package info.dourok.voicebot.data.model

// :feature:form/data/model/ServerFormData.kt
data class ServerFormData(
    val serverType: ServerType = ServerType.XiaoZhi,
    val xiaoZhiConfig: XiaoZhiConfig = XiaoZhiConfig(),
    val selfHostConfig: SelfHostConfig = SelfHostConfig()
)

enum class ServerType {
    XiaoZhi, SelfHost
}

data class XiaoZhiConfig(
    val webSocketUrl: String = "wss://api.tenclass.net/xiaozhi/v1/",
    val qtaUrl: String = "https://api.tenclass.net/xiaozhi/ota/",
    val transportType: TransportType = TransportType.MQTT
)

data class SelfHostConfig(
    val webSocketUrl: String = "ws://192.168.1.246:8000",
    val transportType: TransportType = TransportType.WebSockets // 固定为 WebSockets
)

enum class TransportType {
    MQTT, WebSockets
}

// :feature:form/data/model/ValidationResult.kt
data class ValidationResult(
    val isValid: Boolean,
    val errors: Map<String, String> = emptyMap()
)