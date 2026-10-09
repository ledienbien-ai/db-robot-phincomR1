package info.dourok.voicebot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import info.dourok.voicebot.R
import info.dourok.voicebot.UiState
import info.dourok.voicebot.data.model.SelfHostConfig
import info.dourok.voicebot.data.model.ServerType
import info.dourok.voicebot.data.model.TransportType
import info.dourok.voicebot.data.model.XiaoZhiConfig

// :feature:form/ui/ServerFormScreen.kt
@Composable
fun ServerFormScreen(
    viewModel: FormViewModel = hiltViewModel()
) {
    val formState by viewModel.formState.collectAsState()
    val validationResult by viewModel.validationResult.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ServerTypeSection(
            selectedType = formState.serverType,
            onTypeSelected = viewModel::updateServerType
        )
        
        ServerConfigSection(
            serverType = formState.serverType,
            xiaoZhiConfig = formState.xiaoZhiConfig,
            selfHostConfig = formState.selfHostConfig,
            errors = validationResult.errors,
            onXiaoZhiUpdate = viewModel::updateXiaoZhiConfig,
            onSelfHostUpdate = viewModel::updateSelfHostConfig
        )

        Button(
            onClick = { viewModel.submitForm() },
            enabled = uiState !is UiState.Loading,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.label_conn)) }

        when (val state = uiState) {
            is UiState.Loading -> CircularProgressIndicator()
            is UiState.Success -> Text(state.message, color = MaterialTheme.colorScheme.primary)
            is UiState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
            is UiState.Idle -> {}
        }
    }
}

// :feature:form/ui/components/ServerTypeSection.kt
@Composable
fun ServerTypeSection(
    selectedType: ServerType,
    onTypeSelected: (ServerType) -> Unit
) {
    Column {
        Text("服务器类型", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ServerType.entries.forEach { type ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selectedType == type,
                        onClick = { onTypeSelected(type) }
                    )
                    Text(type.name)
                }
            }
        }
    }
}

// :feature:form/ui/components/ServerConfigSection.kt
@Composable
fun ServerConfigSection(
    serverType: ServerType,
    xiaoZhiConfig: XiaoZhiConfig,
    selfHostConfig: SelfHostConfig,
    errors: Map<String, String>,
    onXiaoZhiUpdate: (XiaoZhiConfig) -> Unit,
    onSelfHostUpdate: (SelfHostConfig) -> Unit
) {
    when (serverType) {
        ServerType.XiaoZhi -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = xiaoZhiConfig.webSocketUrl,
                    onValueChange = { onXiaoZhiUpdate(xiaoZhiConfig.copy(webSocketUrl = it)) },
                    label = { Text("WebSocket URL") },
                    isError = errors.containsKey("xiaoZhiWebSocketUrl"),
                    supportingText = { errors["xiaoZhiWebSocketUrl"]?.let { Text(it) } }
                )
                OutlinedTextField(
                    value = xiaoZhiConfig.qtaUrl,
                    onValueChange = { onXiaoZhiUpdate(xiaoZhiConfig.copy(qtaUrl = it)) },
                    label = { Text("QTA URL") },
                    isError = errors.containsKey("qtaUrl"),
                    supportingText = { errors["qtaUrl"]?.let { Text(it) } }
                )
                Text("传输类型", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TransportType.entries.forEach { type ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = xiaoZhiConfig.transportType == type,
                                onClick = { onXiaoZhiUpdate(xiaoZhiConfig.copy(transportType = type)) }
                            )
                            Text(type.name)
                        }
                    }
                }
            }
        }
        ServerType.SelfHost -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = selfHostConfig.webSocketUrl,
                    onValueChange = { onSelfHostUpdate(selfHostConfig.copy(webSocketUrl = it)) },
                    label = { Text("WebSocket URL") },
                    isError = errors.containsKey("selfHostWebSocketUrl"),
                    supportingText = { errors["selfHostWebSocketUrl"]?.let { Text(it) } }
                )
                Text("传输类型: WebSockets (固定)", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}