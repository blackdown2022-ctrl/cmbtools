package ir.cmbtools.ledger.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.MatchStatus
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.data.BankSms
import ir.cmbtools.ledger.ui.AppViewModel
import ir.cmbtools.ledger.ui.common.CustomerPickerDialog
import ir.cmbtools.ledger.ui.common.EmptyState
import ir.cmbtools.ledger.ui.common.GradientHeader
import ir.cmbtools.ledger.ui.common.SectionCard
import ir.cmbtools.ledger.ui.common.StatusPill
import ir.cmbtools.ledger.ui.theme.AccentAmber
import ir.cmbtools.ledger.ui.theme.AccentGreen
import ir.cmbtools.ledger.ui.theme.AccentRed
import ir.cmbtools.ledger.ui.theme.TextMuted

@Composable
fun BankSmsScreen(vm: AppViewModel, nav: NavHostController) {
    val unmatched by vm.unmatchedSms.collectAsStateWithLifecycle()
    val all by vm.allBankSms.collectAsStateWithLifecycle()
    val customers by vm.customers.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }
    var matchingSms by remember { mutableStateOf<BankSms?>(null) }
    var pickCustomerFor by remember { mutableStateOf<BankSms?>(null) }
    val context = LocalContext.current
    var receiveSmsGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED)
    }
    val receiveSmsPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        receiveSmsGranted = granted
        vm.report(if (granted) "دریافت خودکار پیامک بانکی فعال شد" else "برای دریافت خودکار، مجوز پیامک بانکی لازم است", isError = !granted)
    }

    Column(Modifier.fillMaxSize()) {
        GradientHeader(
            title = "پیامک‌های بانکی",
            subtitle = "${Money.toPersian(unmatched.size.toString())} پیامک در انتظار تطبیق"
        )
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
            listOf("در انتظار تطبیق" to unmatched.size, "همه" to all.size).forEachIndexed { i, pair ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = {
                        Text(
                            "${pair.first} (${Money.toPersian(pair.second.toString())})",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                )
            }
        }

        val list = if (tab == 0) unmatched else all
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!receiveSmsGranted) {
                item {
                    SectionCard(title = "دریافت خودکار پیامک بانکی خاموش است") {
                        Text("برای خواندن پیامک‌های واریز و ثبت خودکار پرداخت، فقط مجوز دریافت پیامک لازم است. اپ در زمان نصب مجوزهای پیامکی را یک‌جا درخواست نمی‌کند.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { receiveSmsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS) }) {
                            Text("فعال‌سازی دریافت پیامک بانکی")
                        }
                    }
                }
            }
            if (list.isEmpty()) {
                item {
                    EmptyState(
                        if (tab == 0) "پیامک بانکی در انتظار نیست" else "پیامکی ثبت نشده",
                        "پیامک‌های واریز به‌صورت خودکار خوانده می‌شوند"
                    )
                }
            }
            items(list, key = { it.id }) { sms ->
                SectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                Money.format(sms.amountRial),
                                color = if (sms.isCredit) AccentGreen else TextMuted,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                sms.bank + " • " + Jalali.formatDateTime(sms.receivedAtMillis),
                                color = TextMuted,
                                style = MaterialTheme.typography.labelMedium
                            )
                            if (sms.last4.isNotBlank()) {
                                Text(
                                    "کارت ****" + Money.toPersian(sms.last4),
                                    color = TextMuted,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                        StatusPill(MatchStatus.label(sms.matchStatus), when (sms.matchStatus) {
                            MatchStatus.AUTO -> AccentGreen
                            MatchStatus.MANUAL -> AccentAmber
                            MatchStatus.IGNORED -> TextMuted
                            else -> AccentRed
                        })
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        sms.body,
                        color = TextMuted,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 3
                    )
                    if (sms.note.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(sms.note, color = AccentAmber, style = MaterialTheme.typography.labelMedium)
                    }
                    if (sms.matchStatus == MatchStatus.UNMATCHED) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { pickCustomerFor = sms }) { Text("تخصیص به مشتری") }
                            TextButton(onClick = { vm.ignoreBankSms(sms.id) }) {
                                Text("نادیده بگیر", color = TextMuted)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    val target = pickCustomerFor
    if (target != null) {
        CustomerPickerDialog(
            customers = customers.map { it.customer },
            title = "این پرداخت برای کدام مشتری است؟",
            onDismiss = { pickCustomerFor = null },
            onPick = { c ->
                pickCustomerFor = null
                matchingSms = target
                vm.confirmBankSms(target.id, c.id, 0)
            }
        )
    }

    if (matchingSms != null) {
        AlertDialog(
            onDismissRequest = { matchingSms = null },
            title = { Text("ثبت شد") },
            text = { Text("پرداخت به‌عنوان «پیامک بانکی» ثبت و به‌صورت خودکار روی قدیمی‌ترین بدهی تخصیص یافت.") },
            confirmButton = { TextButton(onClick = { matchingSms = null }) { Text("باشه") } }
        )
    }
}
