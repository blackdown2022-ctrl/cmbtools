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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import ir.cmbtools.ledger.core.InvoiceStatus
import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.ui.AppViewModel
import ir.cmbtools.ledger.ui.common.CustomerPickerDialog
import ir.cmbtools.ledger.ui.common.EmptyState
import ir.cmbtools.ledger.ui.common.GradientHeader
import ir.cmbtools.ledger.ui.common.SectionCard
import ir.cmbtools.ledger.ui.common.StatCard
import ir.cmbtools.ledger.ui.common.statusColor
import ir.cmbtools.ledger.ui.theme.AccentAmber
import ir.cmbtools.ledger.ui.theme.AccentGreen
import ir.cmbtools.ledger.ui.theme.AccentRed
import ir.cmbtools.ledger.ui.theme.PrimaryCyan
import ir.cmbtools.ledger.ui.theme.TextMuted
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun DashboardScreen(vm: AppViewModel, nav: NavHostController) {
    val summary by vm.dashboard.collectAsStateWithLifecycle()
    val daily by vm.dailySales.collectAsStateWithLifecycle()
    val invoices by vm.invoices.collectAsStateWithLifecycle()
    val accounts by vm.expiringAccounts.collectAsStateWithLifecycle()
    val customers by vm.customers.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var pickCustomerForPayment by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        GradientHeader(
            title = settings.businessName,
            subtitle = Jalali.formatLong(java.time.LocalDate.now().toEpochDay()) + " • " + Jalali.weekdayName(Jalali.today()),
            action = {
                IconButton(onClick = { nav.navigate("settings") }) {
                    Icon(Icons.Default.Settings, contentDescription = "تنظیمات", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard(
                        title = "درآمد این ماه",
                        value = Money.compact(summary.revenueThisMonthRial),
                        accent = AccentGreen,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        title = "مطالبات (بدهکاران)",
                        value = Money.compact(summary.totalReceivableRial),
                        accent = AccentAmber,
                        modifier = Modifier.weight(1f),
                        onClick = { nav.navigate("reports") }
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard(
                        title = "اکانت‌های فعال",
                        value = Money.toPersian(summary.activeAccounts.toString()),
                        accent = PrimaryCyan,
                        modifier = Modifier.weight(1f),
                        subtitle = "${Money.toPersian(summary.expiringIn7Days.toString())} تا ۷ روز آینده منقضی",
                        onClick = { nav.navigate("settings") }
                    )
                    StatCard(
                        title = "پیامک در انتظار",
                        value = Money.toPersian(summary.unmatchedBankSms.toString()),
                        accent = if (summary.unmatchedBankSms > 0) AccentRed else TextMuted,
                        modifier = Modifier.weight(1f),
                        subtitle = "تطبیق دستی لازم است",
                        onClick = { nav.navigate("bank") }
                    )
                }
            }

            item {
                SectionCard(title = "ربات فروش") {
                    Text(
                        if (settings.botPaymentEnabled) "تأیید پیامک واریز برای سفارش‌های مبلغ‌یکتا روشن است"
                        else "تنظیم اتصال سرور ربات و آزمایش وضعیت",
                        color = TextMuted
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { nav.navigate("bot") }) {
                        Text(if (settings.botPaymentEnabled) "تنظیمات ربات" else "ورود به بخش ربات")
                    }
                }
            }

            item {
                SectionCard(title = "فروش ۱۴ روز اخیر") {
                    if (daily.all { it.amountRial == 0L }) {
                        EmptyState("فروشی ثبت نشده", "از تب فاکتور، اولین فاکتور را صادر کنید")
                    } else {
                        val max = daily.maxOf { it.amountRial }.coerceAtLeast(1)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            daily.forEach { point ->
                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Bottom
                                ) {
                                    val ratio = (point.amountRial.toFloat() / max).coerceIn(0.02f, 1f)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height((96 * ratio).dp)
                                            .background(
                                                if (point.amountRial > 0) PrimaryCyan.copy(alpha = 0.75f)
                                                else MaterialTheme.colorScheme.outlineVariant,
                                                RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)
                                            )
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(point.label, fontSize = 10.sp, color = TextMuted)
                                }
                            }
                        }
                    }
                }
            }

            item {
                SectionCard(title = "میان‌برهای سریع") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigAction("فاکتور جدید", "🧾", Modifier.weight(1f)) { vm.requestNewInvoice(); nav.navigate("invoices") }
                        BigAction("ثبت پرداخت", "💵", Modifier.weight(1f)) { pickCustomerForPayment = true }
                        BigAction("بدهکاران", "📉", Modifier.weight(1f)) { nav.navigate("reports") }
                    }
                }
            }

            item {
                SectionCard(title = "آخرین فاکتورها") {
                    val recent = invoices.take(6)
                    if (recent.isEmpty()) {
                        EmptyState("هنوز فاکتوری صادر نشده")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            recent.forEach { invoice ->
                                val customerName = customers.firstOrNull { it.customer.id == invoice.customerId }?.customer?.name ?: "-"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { nav.navigate("invoice/${invoice.id}") },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(Money.toPersian(invoice.number) + " • " + customerName, fontWeight = FontWeight.Medium)
                                        Text(
                                            Jalali.formatEpochDay(invoice.issuedAtEpochDay) + " • " + InvoiceStatus.label(invoice.status),
                                            color = statusColor(invoice.status),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                    Text(Money.format(invoice.totalRial, withUnit = false), color = AccentGreen)
                                }
                            }
                        }
                    }
                }
            }

            item {
                SectionCard(title = "تمدید نزدیک") {
                    if (accounts.isEmpty()) {
                        EmptyState("اکانتی در ۷ روز آینده منقضی نمی‌شود")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            accounts.take(8).forEach { acc ->
                                val owner = customers.firstOrNull { it.customer.id == acc.customerId }?.customer?.name
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(owner ?: acc.name)
                                        Text(
                                            "انقضا: " + Jalali.formatEpochDay(acc.expireEpochDay) +
                                                " • مصرف " + Money.toPersian("%.1f".format(acc.usageGb)) + " از " +
                                                Money.toPersian("%.0f".format(acc.gb)) + " گیگ",
                                            color = TextMuted,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                    Text("تمدید", color = PrimaryCyan, modifier = Modifier.clickable {
                                        if (acc.customerId > 0) nav.navigate("customer/${acc.customerId}") else nav.navigate("settings")
                                    })
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    if (pickCustomerForPayment) {
        CustomerPickerDialog(
            customers = customers.map { it.customer },
            title = "پرداخت برای کدام مشتری؟",
            onDismiss = { pickCustomerForPayment = false },
            onPick = { c ->
                pickCustomerForPayment = false
                nav.navigate("customer/${c.id}")
            }
        )
    }
}

@Composable
private fun BigAction(label: String, emoji: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(emoji, fontSize = 22.sp)
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
