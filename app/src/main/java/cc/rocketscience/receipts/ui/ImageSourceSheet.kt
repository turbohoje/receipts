package cc.rocketscience.receipts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * The pop-out from the + button. A receipt does not always arrive the same way: some are
 * paper in hand, some already exist as a screenshot or a photo taken before the app was
 * opened, and some (a cash tip) have no image at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageSourceSheet(
    onTakePhoto: () -> Unit,
    onChooseFromPhotos: () -> Unit,
    onNoPhoto: () -> Unit,
    onDismiss: () -> Unit,
    title: String = "Add a receipt",
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(bottom = 12.dp).navigationBarsPadding()) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )

            SourceRow(
                icon = Icons.Filled.Add,
                label = "Take a photo",
                supporting = "Use the camera",
                onClick = { onDismiss(); onTakePhoto() },
            )
            SourceRow(
                icon = Icons.Filled.Search,
                label = "Choose from photos",
                supporting = "Pick an image already on the phone",
                onClick = { onDismiss(); onChooseFromPhotos() },
            )
            SourceRow(
                icon = Icons.Filled.Edit,
                label = "No photo",
                supporting = "Just enter a description and amount",
                onClick = { onDismiss(); onNoPhoto() },
            )
        }
    }
}

@Composable
private fun SourceRow(
    icon: ImageVector,
    label: String,
    supporting: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(10.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(0.dp))
}
