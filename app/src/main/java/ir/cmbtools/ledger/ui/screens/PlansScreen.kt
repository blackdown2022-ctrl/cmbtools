package ir.cmbtools.ledger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.data.Plan
import ir.cmbtools.ledger.ui.AppViewModel
import ir.cmbtools.ledger.ui.common.AmountField
import ir.cmbtools.ledger.ui.common.EmptyState
import ir.cmbtools.ledger.ui.common.GradientHeader
import ir.cmbtools.ledger.ui.common.LabeledField
import ir.cmbtools.ledger.ui.theme.AccentGreen
import ir.cmbtools.ledger.ui.theme.AccentRed
import ir.cmbtools.ledger.ui.theme.TextMuted

@Composable
fun PlansScreen(vm: AppViewModel, nav: NavHostController) {
    val plans by vm.allPlans.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Plan?>(null) }
    var showNew by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showNew = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) { Icon(Icons.Default.Add, contentDescription = "بستهٔ جدید") }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            GradientHeader(
                title = "بسته‌ها",
                subtitle = "قیمت و حجم و روز — پایهٔ صدور فاکتور",
                action = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت", tint = TextMuted)
                    }
                }
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (plans.isEmpty()) {
                    item { EmptyState("بسته‌ای ثبت نشده") }
                }
                items(plans, key = { it.id }) { plan ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(plan.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${Money.toPersian(plan.gb.toString())} گیگ • ${Money.toPersian(plan.days.toString())} روز",
                                color = TextMuted,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Money.format(plan.priceRial, withUnit = false), color = AccentGreen)
                            Row {
                                TextButton(onClick = { editing = plan }) { Text("ویرایش") }
                                TextButton(onClick = { vm.deletePlan(plan.id) }) {
                                    Text("حذف", color = AccentRed)
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    if (showNew || editing != null) {
        PlanEditor(
            vm = vm,
            plan = editing,
            onDismiss = {
                showNew = false
                editing = null
            }
        )
    }
}

@Composable
private fun PlanEditor(vm: AppViewModel, plan: Plan?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(plan?.name ?: "") }
    var gb by remember { mutableStateOf(plan?.gb?.toString() ?: "30") }
    var days by remember { mutableStateOf(plan?.days?.toString() ?: "30") }
    var price by remember { mutableStateOf(plan?.priceRial ?: 0L) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (plan == null) "بستهٔ جدید" else "ویرایش بسته") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledField("نام", name, { name = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField(
                        "حجم (گیگ)",
                        gb,
                        { gb = it.filter { ch -> ch.isDigit() } },
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number
                    )
                    LabeledField(
                        "روز",
                        days,
                        { days = it.filter { ch -> ch.isDigit() } },
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number
                    )
                }
                AmountField("قیمت (ریال)", price, { price = it })
            }
        },
        confirmButton = {
            Button(onClick = {
                vm.savePlan(
                    id = plan?.id ?: 0L,
                    name = name,
                    gb = gb.toIntOrNull() ?: 0,
                    days = days.toIntOrNull() ?: 0,
                    priceRial = price
                ) { onDismiss() }
            }) { Text("ذخیره") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}
