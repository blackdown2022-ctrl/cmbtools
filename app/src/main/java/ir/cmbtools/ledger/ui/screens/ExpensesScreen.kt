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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.ui.AppViewModel
import ir.cmbtools.ledger.ui.common.AmountField
import ir.cmbtools.ledger.ui.common.EmptyState
import ir.cmbtools.ledger.ui.common.GradientHeader
import ir.cmbtools.ledger.ui.common.JalaliDateField
import ir.cmbtools.ledger.ui.common.LabeledField
import ir.cmbtools.ledger.ui.theme.AccentRed
import ir.cmbtools.ledger.ui.theme.TextMuted
import java.time.LocalDate

@Composable
fun ExpensesScreen(vm: AppViewModel, nav: NavHostController) {
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("سرور") }
    var amount by remember { mutableStateOf(0L) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var note by remember { mutableStateOf("") }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAdd = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) { Icon(Icons.Default.Add, contentDescription = "هزینهٔ جدید") }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            GradientHeader(
                title = "هزینه‌ها",
                subtitle = "برای محاسبهٔ سود واقعی (سرور، پهنای‌باند، پیامک…)",
                action = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (expenses.isEmpty()) {
                    item { EmptyState("هزینه‌ای ثبت نشده") }
                }
                items(expenses, key = { it.id }) { expense ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(expense.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                expense.category + " • " + Jalali.formatEpochDay(expense.atEpochDay),
                                color = TextMuted,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(Money.format(expense.amountRial, withUnit = false), color = AccentRed)
                            TextButton(onClick = { vm.deleteExpense(expense.id) }) {
                                Text("حذف", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("هزینهٔ جدید") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField("عنوان", title, { title = it })
                    LabeledField("دسته (سرور/پهنای‌باند/پیامک/سایر)", category, { category = it })
                    AmountField("مبلغ (ریال)", amount, { amount = it })
                    JalaliDateField("تاریخ", date, { date = it })
                    LabeledField("یادداشت", note, { note = it })
                }
            },
            confirmButton = {
                Button(enabled = title.isNotBlank() && amount > 0, onClick = {
                    vm.addExpense(title, category, amount, date, note)
                    title = ""; amount = 0; note = ""
                    showAdd = false
                }) { Text("ثبت") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("انصراف") } }
        )
    }
}
