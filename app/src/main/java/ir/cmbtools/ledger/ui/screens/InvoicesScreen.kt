package ir.cmbtools.ledger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import ir.cmbtools.ledger.core.InvoiceKind
import ir.cmbtools.ledger.core.InvoiceStatus
import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.ui.AppViewModel
import ir.cmbtools.ledger.ui.common.EmptyState
import ir.cmbtools.ledger.ui.common.SearchField
import ir.cmbtools.ledger.ui.common.StatusPill
import ir.cmbtools.ledger.ui.common.statusColor
import ir.cmbtools.ledger.ui.theme.AccentGreen
import ir.cmbtools.ledger.ui.theme.AccentRed
import ir.cmbtools.ledger.ui.theme.PrimaryCyan
import ir.cmbtools.ledger.ui.theme.TextMuted

@Composable
fun InvoicesScreen(vm: AppViewModel, nav: NavHostController) {
    val invoices by vm.invoices.collectAsStateWithLifecycle()
    val customers by vm.customers.collectAsStateWithLifecycle()
    val request by vm.newInvoiceRequest.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("ALL") }
    var showNew by remember { mutableStateOf(false) }
    var presetCustomer by remember { mutableStateOf(0L) }

    LaunchedEffect(request) {
        if (request != 0L) {
            presetCustomer = if (request > 0) request else 0L
            showNew = true
            vm.consumeNewInvoiceRequest()
        }
    }

    val customerNames = remember(customers) { customers.associate { it.customer.id to it.customer.name } }
    val filtered = remember(query, filter, invoices, customerNames) {
        invoices.filter { inv ->
            val matchesQuery = query.isBlank() ||
                inv.number.contains(query) ||
                (customerNames[inv.customerId] ?: "").contains(query, true)
            val matchesFilter = when (filter) {
                "OPEN" -> inv.status != InvoiceStatus.PAID && inv.status != InvoiceStatus.VOID
                "PAID" -> inv.status == InvoiceStatus.PAID
                else -> true
            }
            matchesQuery && matchesFilter
        }
    }

    val totalOpen = invoices.filter { it.status != InvoiceStatus.PAID && it.status != InvoiceStatus.VOID }
        .sumOf { it.remainingRial }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { presetCustomer = 0; showNew = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) { Icon(Icons.Default.Add, contentDescription = "فاکتور جدید") }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { SearchField(query, { query = it }, "شمارهٔ فاکتور یا نام مشتری") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ALL" to "همه", "OPEN" to "پرداخت‌نشده", "PAID" to "تسویه").forEach { (key, label) ->
                        Text(
                            label,
                            modifier = Modifier
                                .background(
                                    if (filter == key) PrimaryCyan.copy(alpha = 0.18f)
                                    else MaterialTheme.colorScheme.surface,
                                    RoundedCornerShape(50)
                                )
                                .clickable { filter = key }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            color = if (filter == key) PrimaryCyan else TextMuted,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            item {
                Text(
                    "ماندهٔ کل فاکتورهای باز: " + Money.format(totalOpen),
                    color = AccentGreen,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            if (filtered.isEmpty()) {
                item { EmptyState("فاکتوری پیدا نشد") }
            }

            items(filtered, key = { it.id }) { inv ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                        .clickable { nav.navigate("invoice/${inv.id}") }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${inv.number} • ${customerNames[inv.customerId] ?: "-"}",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            Jalali.formatEpochDay(inv.issuedAtEpochDay) +
                                (if (inv.packageName.isNotBlank()) " • " + inv.packageName else "") +
                                (if (inv.kind != InvoiceKind.MANUAL) " • " + InvoiceKind.label(inv.kind) else ""),
                            color = TextMuted,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Money.format(inv.totalRial, withUnit = false), color = AccentGreen)
                        if (inv.remainingRial > 0 && inv.status != InvoiceStatus.VOID) {
                            Text(
                                "مانده " + Money.format(inv.remainingRial, withUnit = false),
                                color = AccentRed,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        StatusPill(InvoiceStatus.label(inv.status), statusColor(inv.status))
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (showNew) {
        NewInvoiceDialog(
            vm = vm,
            presetCustomerId = presetCustomer,
            onDismiss = { showNew = false },
            onIssued = { id ->
                showNew = false
                nav.navigate("invoice/$id")
            }
        )
    }
}
