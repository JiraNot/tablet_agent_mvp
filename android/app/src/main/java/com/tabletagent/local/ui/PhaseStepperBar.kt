package com.tabletagent.local.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    val normalizedState = state.uppercase()
    val activeStep = when (normalizedState) {
        "PLANNING" -> 1
        "EXECUTING" -> 2
        "VERIFYING" -> 3
        "REPAIRING" -> 4
        "COMPLETED", "PLANNED" -> 5
        "NEEDS_VERIFICATION" -> 3
        else -> 0
    }

    val isTerminal = normalizedState in listOf("COMPLETED", "PLANNED", "FAILED", "CANCELLED")

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Workflow Phase",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                if (normalizedState.isNotBlank()) {
                    Surface(
                        color = when (normalizedState) {
                            "COMPLETED" -> MaterialTheme.colorScheme.primaryContainer
                            "FAILED" -> MaterialTheme.colorScheme.errorContainer
                            "PLANNING", "EXECUTING", "VERIFYING", "REPAIRING" -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            normalizedState,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PhaseStep.values().forEachIndexed { index, step ->
                    val isPassed = activeStep > step.id || (activeStep == 5 && isTerminal)
                    val isCurrent = activeStep == step.id && !isTerminal
                    val isNext = activeStep < step.id

                    val stepColor = when {
                        isCurrent -> MaterialTheme.colorScheme.primary
                        isPassed -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.outlineVariant
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = when {
                                isCurrent -> MaterialTheme.colorScheme.primaryContainer
                                isPassed -> MaterialTheme.colorScheme.tertiaryContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(stepColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        step.icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.surface,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${step.id}. ${step.title}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        if (index < PhaseStep.values().size - 1) {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.size(16.dp).padding(horizontal = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
