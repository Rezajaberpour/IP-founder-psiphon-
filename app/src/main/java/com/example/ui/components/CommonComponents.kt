package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberLatency
import com.example.ui.theme.CoralContainer
import com.example.ui.theme.CoralError
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricCyanContainer
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.VioletContainer
import com.example.ui.theme.VioletStudio

@Composable
fun IpVersionBadge(
    ipVersion: String,
    modifier: Modifier = Modifier
) {
    val isV6 = ipVersion.equals("IPv6", ignoreCase = true)
    val bg = if (isV6) VioletContainer else ElectricCyanContainer
    val fg = if (isV6) VioletStudio else ElectricCyan

    Surface(
        modifier = modifier,
        color = bg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.35f))
    ) {
        Text(
            text = ipVersion,
            color = fg,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun ProtocolBadge(
    protocol: String,
    modifier: Modifier = Modifier
) {
    val isSocks5 = protocol.equals("SOCKS5", ignoreCase = true)
    val bg = if (isSocks5) EmeraldContainer else VioletContainer
    val fg = if (isSocks5) EmeraldShield else VioletStudio

    Surface(
        modifier = modifier,
        color = bg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.4f))
    ) {
        Text(
            text = if (isSocks5) "SOCKS5" else "HTTP",
            color = fg,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun PingBadge(
    pingMs: Long?,
    isClean: Boolean,
    modifier: Modifier = Modifier
) {
    val (label, bg, fg) = when {
        !isClean || pingMs == null -> Triple(stringResource(R.string.ping_failed), CoralContainer, CoralError)
        pingMs < 180L -> Triple(stringResource(R.string.ping_ms_format, pingMs), EmeraldContainer, EmeraldShield)
        pingMs < 450L -> Triple(stringResource(R.string.ping_ms_format, pingMs), AmberContainer, AmberLatency)
        else -> Triple(stringResource(R.string.ping_ms_format, pingMs), CoralContainer, CoralError)
    }

    Surface(
        modifier = modifier,
        color = bg,
        shape = RoundedCornerShape(999.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(fg)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = fg,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1
            )
        }
    }
}

@Composable
fun TelemetryStatCard(
    label: String,
    value: String,
    accent: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun AiScoreBadge(
    aiScore: Int,
    aiVerdict: String,
    modifier: Modifier = Modifier
) {
    val (bg, fg) = when {
        aiVerdict.equals("OPTIMAL", ignoreCase = true) || aiScore >= 82 -> EmeraldContainer to EmeraldShield
        aiVerdict.equals("RESILIENT", ignoreCase = true) || aiScore >= 64 -> ElectricCyanContainer to ElectricCyan
        aiVerdict.equals("MODERATE", ignoreCase = true) || aiScore >= 42 -> AmberContainer to AmberLatency
        else -> CoralContainer to CoralError
    }

    Surface(
        modifier = modifier,
        color = bg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.4f))
    ) {
        Text(
            text = "AI $aiScore",
            color = fg,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun AnonymityConfidenceBadge(
    confidenceScore: Int,
    modifier: Modifier = Modifier
) {
    val (bg, fg) = when {
        confidenceScore >= 80 -> EmeraldContainer to EmeraldShield
        confidenceScore >= 60 -> ElectricCyanContainer to ElectricCyan
        confidenceScore >= 40 -> AmberContainer to AmberLatency
        else -> CoralContainer to CoralError
    }

    Surface(
        modifier = modifier,
        color = bg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.4f))
    ) {
        Text(
            text = stringResource(R.string.anonymity_badge_format, confidenceScore),
            color = fg,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}
