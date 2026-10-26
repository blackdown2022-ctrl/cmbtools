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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.export.ExportManager
import ir.cmbtools.ledger.ui.AppViewModel
import ir.cmbtools.ledger.ui.common.EmptyState
import ir.cmbtools.ledger.ui.common.GradientHeader
import ir.cmbtools.ledger.ui.common.SectionCard
import ir.cmbtools.ledger.ui.common.StatCard
import ir.cmbtools.ledger.ui.theme.AccentAmber
import ir.cmbtools.ledger.ui.theme.AccentGreen
import ir.cmbtools.ledger.ui.theme.AccentRed
import ir.cmbtools.ledger.ui.theme.PrimaryCyan
import ir.cmbtools.ledger.ui.theme.TextMuted
import java.time.LocalDate

@Composable
fun ReportsScreen(vm: AppViewModel, nav: NavHostController) {
    val summary by vm.dashboard.collectAsStateWithLifecycle()
    val aging by vm.aging.collectAsStateWithLifecycle()
    val customers by vm.customers.collectAsStateWithLifecycle()
    val invoices by vm.invoices.collectAsStateWithLifecycle()
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val debtors = customers.filter { it.balanceRial > 0 }.sortedByDescending { it.balanceRial }
    val paidCount = invoices.count { it.status == "PAID" }
    val totalInvoiced = invoices.filter { it.status != "VOID" }.sumOf { it.totalRial }
    val totalPaid = invoices.sumOf { it.paidRial }

    Column(Modifier.fillMaxSize()) {
        GradientHeader(title = "گزارش‌ها", subtitle = "نمای مالی و عملیاتی کسب‌وکار")
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(
                    "فروش کل",
                    Money.compact(totalInvoiced),
                    Modifier.weight(1f),
                    AccentGreen
                )
                StatCard("وصول‌شده", Money.compact(totalPaid), Modifier.weight(1f), PrimaryCyan)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(
                    "مطالبات",
                    Money.compact(summary.totalReceivableRial),
                    Modifier.weight(1f),
                    AccentAmber
                )
                StatCard(
                    "سود این ماه",
                    Money.compact(summary.profitThisMonthRial),
                    Modifier.weight(1f),
                    if (summary.profitThisMonthRial >= 0) AccentGreen else AccentRed,
                    subtitle = "درآمد − هزینه"
                )
            }

            SectionCard(title = "سنی بدهی (Aging)") {
                if (aging.totalRial == 0L) {
                    EmptyState("بدهی معوقی وجود ندارد")
                } else {
                    aging.buckets.forEach { bucket ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(bucket.label, color = TextMuted, style = MaterialTheme.typography.labelMedium)
                            Text(
                                Money.format(bucket.amountRial, withUnit = false) +
                                    "  (${Money.toPersian(bucket.customers.toString())})",
                                color = if (bucket.amountRial > 0) AccentAmber else TextMuted
                            )
                        }
                    }
                }
            }

            SectionCard(title = "بدهکاران") {
                if (debtors.isEmpty()) {
                    EmptyState("همه تسویه‌اند 👌")
                } else {
                    debtors.take(12).forEach { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { nav.navigate("customer/${row.customer.id}") }
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(row.customer.name)
                            Text(
                                Money.format(row.balanceRial, withUnit = false),
                                color = if (row.overLimit) AccentRed else AccentAmber
                            )
                        }
                    }
                }
            }

            SectionCard(title = "هزینه‌ها") {
                Text(
                    "این ماه: " + Money.format(summary.expenseThisMonthRial),
                    color = AccentRed,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                expenses.take(4).forEach { e ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(e.title, style = MaterialTheme.typography.bodyMedium)
                        Text(Money.format(e.amountRial, withUnit = false), color = TextMuted)
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { nav.navigate("expenses") }) { Text("مدیریت هزینه‌ها") }
            }

            SectionCard(title = "خروجی گرفتن") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            ExportManager(context, vm.appContainer())
                                .exportInvoicesCsv(invoices, customers.associate { it.customer.id to it.customer.name })
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Excel فاکتورها") }
                    OutlinedButton(
                        onClick = {
                            ExportManager(context, vm.appContainer())
                                .exportCustomersCsv(customers)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Excel مشتریان") }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "پشتیبان‌گیری و بازیابی فقط در «تنظیمات ← پشتیبان» است تا جای اشتباه نباشد. " +
                        "اینجا فقط خروجی Excel گرفته می‌شود.",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { vm.navigate("settings") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("رفتن به پشتیبان‌گیری در تنظیمات") }
            }

            Text(
                "تاریخ گزارش: " + ir.cmbtools.ledger.core.Jalali.format(LocalDate.now()),
                color = TextMuted,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}
