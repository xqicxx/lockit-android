package com.lockit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.JetBrainsMonoFamily

data class QuickProvider(
    val key: String,
    val label: String,
    val usesWebView: Boolean,
)

/**
 * Grid of coding plan provider quick-select buttons.
 * Each provider knows whether it needs WebView auth or just field update.
 */
@Composable
fun CodingPlanQuickSelect(
    providers: List<QuickProvider>,
    onSelect: (QuickProvider) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = providers.chunked(2)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "QUICK SELECT",
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = IndustrialOrange,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        rows.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { provider ->
                    BrutalistButton(
                        text = provider.label.uppercase(),
                        onClick = { onSelect(provider) },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                        useMonoFont = true,
                    )
                }
                // If odd number, fill with spacer
                if (rowItems.size < 2) {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(vertical = 4.dp))
        }
    }
}
