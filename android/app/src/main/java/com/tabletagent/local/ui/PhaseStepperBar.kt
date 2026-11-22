package com.tabletagent.local.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class PhaseStep(val id: Int, val title: String, val icon: ImageVector) {
    PLAN(1, "Plan", Icons.Default.Description),
    EXECUTE(2, "Execute", Icons.Default.PlayArrow),
    VERIFY(3, "Verify", Icons.Default.CheckCircle),
    REPAIR(4, "Auto-Repair", Icons.Default.Build),
    COMMIT(5, "Commit", Icons.Default.Commit)
}

@Composable
fun PhaseStepperBar(
    state: String,
    modifier: Modifier = Modifier,
    onCommitClick: () -> Unit = {}
) {
    val normalizedState = state.uppercase().trim()
    if (normalizedState.isBlank()) return

    val (stepIndex, phaseLabel, phaseIcon) = when (normalizedState) {
        "QUEUED" -> Triple(1, "Queued · Preparing workspace", Icons.Default.Schedule)
        "PLANNING" -> Triple(1, "Step 1/5 · Planning architecture", Icons.Default.Description)
        "WAITING_APPROVAL" -> Triple(2, "Step 2/5 · Waiting for user approval", Icons.Default.Security)
        "EXECUTING" -> Triple(2, "Step 2/5 · Executing agent changes", Icons.Default.PlayArrow)
        "VERIFYING" -> Triple(3, "Step 3/5 · Verifying build & tests", Icons.Default.Verified)
        "REPAIRING" -> Triple(4, "Step 4/5 · Auto-repairing errors", Icons.Default.Build)
        "PLANNED" -> Triple(5, "Plan Ready · Review steps below", Icons.Default.CheckCircle)
        "COMPLETED" -> Triple(5, "Verified · Ready to Commit", Icons.Default.CheckCircle)
        "NEEDS_VERIFICATION" -> Triple(3, "Done · Manual verification needed", Icons.Default.Info)
        "FAILED" -> Triple(3, "Task Failed · Inspect error below", Icons.Default.Error)
        "CANCELLED" -> Triple(0, "Cancelled by user", Icons.Default.Cancel)
        "INTERRUPTED" -> Triple(0, "Interrupted · Bridge restarted", Icons.Default.Warning)
        else -> Triple(1, normalizedState, Icons.Default.Info)
    }

    val isSuccess = normalizedState in listOf("COMPLETED", "PLANNED")
    val isError = normalizedState in listOf("FAILED", "CANCELLED", "INTERRUPTED", "BLOCKED_SETUP")
    val isRunning = normalizedState in listOf("QUEUED", "PLANNING", "WAITING_APPROVAL", "EXECUTING", "VERIFYING", "REPAIRING")

    val accentColor = when {
        isSuccess -> MaterialTheme.colorScheme.tertiary
        isError -> MaterialTheme.colorScheme.error
        isRunning -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val containerBg = when {
        isSuccess -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
        isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }

    Surface(
        color = containerBg,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.28f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            // Active single phase icon + dynamic title
            Surface(
                color = accentColor,
                shape = CircleShape,
                modifier = Modifier.size(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        phaseIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Text(
                phaseLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )

            // 5 mini progress pills showing workflow position without text overflow
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 6.dp)
            ) {
                for (i in 1..5) {
                    val dotColor = when {
                        isError && i == stepIndex -> MaterialTheme.colorScheme.error
                        i < stepIndex || (isSuccess && i <= 5) -> MaterialTheme.colorScheme.tertiary
                        i == stepIndex -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outlineVariant
                    }
                    Box(
                        modifier = Modifier
                            .width(if (i == stepIndex && isRunning) 14.dp else 6.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(dotColor)
                    )
                }
            }

            Spacer(Modifier.width(4.dp))

            // If COMPLETED, provide a direct clickable "Commit" action chip right on the bar
            if (normalizedState == "COMPLETED") {
                Surface(
                    onClick = onCommitClick,
                    color = MaterialTheme.colorScheme.tertiary,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            Icons.Default.Commit,
                            contentDescription = "Commit",
                            tint = MaterialTheme.colorScheme.onTertiary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Commit",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiary
                        )
                    }
                }
            } else {
                Surface(
                    color = accentColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        normalizedState,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
