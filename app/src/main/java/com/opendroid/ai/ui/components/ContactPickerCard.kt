package com.opendroid.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.core.agent.maskPhone
import com.opendroid.ai.ui.theme.*

/**
 * Contact picker card shown when multiple contacts match a query.
 * Displays up to 5 options with numbered badges and masked phone numbers.
 */
@Composable
fun ContactPickerCard(
    query: String,
    matches: List<Map<String, String>>,
    onContactSelected: (Map<String, String>) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A2E)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, AccentNeonGreen),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = AccentNeonGreen,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Which '$query' do you mean?",
                    color = AccentNeonGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Contact options
            matches.forEachIndexed { index, contact ->
                ContactOptionRow(
                    number = index + 1,
                    name = contact["name"] ?: "",
                    phone = maskPhone(contact["phone"] ?: ""),
                    type = contact["type"] ?: "Mobile",
                    onClick = { onContactSelected(contact) }
                )
                if (index < matches.size - 1) {
                    Divider(
                        color = Color(0xFF2A2A3E),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Hint
            Text(
                text = "Tap to select, or type the number (1, 2, 3...)",
                color = TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun ContactOptionRow(
    number: Int,
    name: String,
    phone: String,
    type: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Number badge
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    AccentNeonGreen.copy(alpha = 0.2f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$number",
                color = AccentNeonGreen,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            )
            Text(
                text = "$type • $phone",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }

        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = AccentNeonGreen.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}
