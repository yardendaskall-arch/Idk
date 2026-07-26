package com.microdose.discipline.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.microdose.discipline.R
import com.microdose.discipline.data.TargetApp

data class PermissionRowState(
    val title: String,
    val description: String,
    val granted: Boolean,
    val onGrantClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    permissions: List<PermissionRowState>,
    targetApps: List<TargetApp>,
    selectedPackages: Set<String>,
    onToggleTargetApp: (String, Boolean) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).padding(16.dp)) {
            item {
                Text(
                    text = stringResource(R.string.onboarding_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            items(permissions) { perm ->
                PermissionRow(perm)
                Divider()
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = stringResource(R.string.target_apps_title), style = MaterialTheme.typography.titleMedium)
            }

            items(targetApps) { app ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = app.packageName in selectedPackages,
                        onCheckedChange = { checked -> onToggleTargetApp(app.packageName, checked) },
                    )
                    Text(text = app.displayName)
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.add_widget_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(state: PermissionRowState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(text = state.title, style = MaterialTheme.typography.titleSmall)
            Text(text = state.description, style = MaterialTheme.typography.bodySmall)
        }
        if (state.granted) {
            Text(text = stringResource(R.string.perm_granted))
        } else {
            Button(onClick = state.onGrantClick) {
                Text(text = stringResource(R.string.perm_grant))
            }
        }
    }
}
