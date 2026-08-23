package cc.rocketscience.receipts.ui.receipt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.rocketscience.receipts.ui.ConfirmDialog
import cc.rocketscience.receipts.ui.formatDate
import cc.rocketscience.receipts.ui.pickedDateToStoredMillis
import cc.rocketscience.receipts.ui.storedMillisToPickerMillis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptEditScreen(
    factory: ViewModelProvider.Factory,
    onDone: () -> Unit,
) {
    val vm: ReceiptEditViewModel = viewModel(factory = factory)

    var showDatePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "Add receipt" else "Edit receipt") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!vm.isNew) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete receipt")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Phase 2 puts the captured image above these fields.

            OutlinedTextField(
                value = vm.description,
                onValueChange = vm::onDescriptionChange,
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )

            OutlinedTextField(
                value = vm.amountText,
                onValueChange = vm::onAmountChange,
                label = { Text("Amount (${vm.currency.currencyCode})") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = vm.amountInvalid,
                supportingText = if (vm.amountInvalid) {
                    { Text("Enter an amount, e.g. 24.50") }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
            )

            OutlinedTextField(
                value = formatDate(vm.date),
                onValueChange = {},
                label = { Text("Date") },
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = { showDatePicker = true }) { Text("Change") }
                },
            )

            Button(
                onClick = { vm.save(onDone) },
                enabled = vm.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = storedMillisToPickerMillis(vm.date),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let {
                            vm.onDateChange(pickedDateToStoredMillis(it))
                        }
                        showDatePicker = false
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete receipt?",
            body = "Delete \"${vm.description.ifBlank { "this receipt" }}\"? " +
                "This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = { vm.delete(onDone) },
            onDismiss = { confirmDelete = false },
        )
    }
}
