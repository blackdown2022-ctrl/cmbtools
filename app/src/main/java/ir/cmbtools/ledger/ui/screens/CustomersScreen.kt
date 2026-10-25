package ir.cmbtools.ledger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.data.CustomerRow
import ir.cmbtools.ledger.ui.AppViewModel
import ir.cmbtools.ledger.ui.common.AmountField
import ir.cmbtools.ledger.ui.common.EmptyState
import ir.cmbtools.ledger.ui.common.LabeledField
import ir.cmbtools.ledger.ui.common.SearchField
import ir.cmbtools.ledger.ui.theme.AccentAmber
import ir.cmbtools.ledger.ui.theme.AccentGreen
import ir.cmbtools.ledger.ui.theme.AccentRed
import ir.cmbtools.ledger.ui.theme.PrimaryCyan
import ir.cmbtools.ledger.ui.theme.TextMuted

@Composable
fun CustomersScreen(vm: AppViewModel, nav: NavHostController) {
    val rows by vm.customers.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }

    val filtered = remember(query, rows) {
        if (query.isBlank()) rows
        else rows.filter { it.customer.name.contains(query, true) || it.customer.phone.contains(query) }
    }
    val debtors = filtered.filter { it.balanceRial > 0 }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAdd = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) { Icon(Icons.Default.Add, contentDescription = "مشتری جدید") }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SearchField(query, { query = it }, "نام یا شمارهٔ مشتری")
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${Money.toPersian(filtered.size.toString())} مشتری",
                        color = TextMuted,
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (debtors.isNotEmpty()) {
                        Text(
                            "• ${Money.toPersian(debtors.size.toString())} بدهکار • " + Money.compact(debtors.sumOf { it.balanceRial }),
                            color = AccentAmber,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            if (filtered.isEmpty()) {
                item { EmptyState("مشتری‌ای ثبت نشده", "با دکمهٔ + اولین مشتری را اضافه کنید") }
            }

            items(filtered, key = { it.customer.id }) { row ->
                CustomerRowCard(row) { nav.navigate("customer/${row.customer.id}") }
            }

            item { Spacer(Modifier.height(70.dp)) }
        }
    }

    if (showAdd) {
        CustomerEditorDialog(
            vm = vm,
            existing = null,
            onDismiss = { showAdd = false },
            onSaved = { id ->
                showAdd = false
                nav.navigate("customer/$id")
            }
        )
    }
}

@Composable
fun CustomerRowCard(row: CustomerRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(row.customer.name.firstOrNull()?.toString() ?: "?", color = PrimaryCyan, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(row.customer.name, fontWeight = FontWeight.SemiBold)
            Row {
                if (row.customer.phone.isNotBlank()) {
                    Text(Money.toPersian(row.customer.phone), color = TextMuted, style = MaterialTheme.typography.labelMedium)
                }
                if (row.overLimit) {
                    Text("  • سقف اعتبار رد شد", color = AccentRed, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                Money.format(row.balanceRial, withUnit = false),
                color = when {
                    row.balanceRial > 0 -> AccentAmber
                    row.balanceRial < 0 -> AccentGreen
                    else -> TextMuted
                },
                fontWeight = FontWeight.Bold
            )
            Text(
                when {
                    row.balanceRial > 0 -> "بدهکار"
                    row.balanceRial < 0 -> "بستانکار"
                    else -> "تسویه"
                },
                color = TextMuted,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
fun CustomerEditorDialog(
    vm: AppViewModel,
    existing: ir.cmbtools.ledger.data.Customer?,
    onDismiss: () -> Unit,
    onSaved: (Long) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: "") }
    var card by remember { mutableStateOf(existing?.cardLast4 ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var credit by remember { mutableStateOf(existing?.creditLimitRial ?: 0L) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "مشتری جدید" else "ویرایش مشتری") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledField("نام", name, { name = it })
                LabeledField("موبایل", phone, { phone = SmsDigitFilter(it) }, keyboardType = KeyboardType.Phone)
                LabeledField(
                    "۴ رقم آخر کارت (برای تطبیق پیامک بانکی)",
                    card,
                    { card = it.filter { ch -> ch.isDigit() }.take(4) },
                    keyboardType = KeyboardType.Number
                )
                AmountField("سقف اعتبار (ریال، ۰ = بدون سقف)", credit, { credit = it })
                LabeledField("یادداشت", note, { note = it }, singleLine = false)
            }
        },
        confirmButton = {
            Button(onClick = {
                vm.saveCustomer(existing?.id ?: 0L, name, phone, card, note, credit) { id -> onSaved(id) }
            }) { Text("ذخیره") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

private fun SmsDigitFilter(input: String): String =
    Money.toLatinDigits(input).filter { it.isDigit() || it == '+' }
