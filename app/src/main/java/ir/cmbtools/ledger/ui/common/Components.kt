package ir.cmbtools.ledger.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.ui.theme.AccentAmber
import ir.cmbtools.ledger.ui.theme.AccentGreen
import ir.cmbtools.ledger.ui.theme.AccentRed
import ir.cmbtools.ledger.ui.theme.TextMuted
import java.time.LocalDate

@Composable
fun MoneyText(amountRial: Long, color: Color? = null, bold: Boolean = false, withUnit: Boolean = true) {
    Text(
        text = Money.format(amountRial, withUnit = withUnit),
        color = color ?: MaterialTheme.colorScheme.onSurface,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        style = MaterialTheme.typography.bodyLarge
    )
}

@Composable
fun StatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, color = TextMuted, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            Text(value, color = accent, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = TextMuted, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

@Composable
fun EmptyState(text: String, hint: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text, color = TextMuted)
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(hint, color = TextMuted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    placeholder: String? = null,
    maxLines: Int = if (singleLine) 1 else 5,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = TextMuted) } },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        maxLines = maxLines,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(14.dp)
    )
}

/** فیلد مبلغ: ورودی تومان/ریال با تبدیل شفاف (ذخیره همیشه ریال) */
@Composable
fun AmountField(
    label: String,
    valueRial: Long,
    onValueChangeRial: (Long) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "به ریال"
) {
    var text by remember(valueRial) {
        mutableStateOf(if (valueRial == 0L) "" else Money.toPersian(valueRial.toString()))
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val digits = Money.toLatinDigits(input).filter { it.isDigit() }
            text = if (digits.isEmpty()) "" else Money.toPersian(digits)
            onValueChangeRial(digits.toLongOrNull() ?: 0L)
        },
        label = { Text(label) },
        suffix = { Text(hint, color = TextMuted, style = MaterialTheme.typography.labelMedium) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(14.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DropdownField(
    label: String,
    items: List<T>,
    selected: T?,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selected?.let(itemLabel) ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            shape = RoundedCornerShape(14.dp)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(itemLabel(item)) },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** انتخاب تاریخ شمسی */
@Composable
fun JalaliDateField(
    label: String,
    date: LocalDate,
    onChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedTextField(
            value = Jalali.format(date),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        )
        // یک لایهٔ شفاف روی فیلد تا لمس، همیشه تقویم را باز کند
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { showDialog = true }
        )
        if (showDialog) {
            JalaliPickerDialog(
                initial = date,
                onDismiss = { showDialog = false },
                onPick = {
                    onChange(it)
                    showDialog = false
                }
            )
        }
    }
}

@Composable
fun JalaliPickerDialog(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit
) {
    val j = Jalali.fromGregorian(initial)
    var year by remember { mutableStateOf(j.year) }
    var month by remember { mutableStateOf(j.month) }
    var day by remember { mutableStateOf(j.day) }

    val monthNames = (1..12).map { Jalali.monthName(it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("انتخاب تاریخ") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { year -= 1 }) { Text("−", fontSize = 20.sp) }
                    Text(Money.toPersian(year.toString()), modifier = Modifier.padding(horizontal = 12.dp))
                    IconButton(onClick = { year += 1 }) { Text("+", fontSize = 18.sp) }
                    Spacer(Modifier.width(16.dp))
                    Text(Jalali.weekdayName(Jalali.JDate(year, month, day)), color = TextMuted)
                }
                Spacer(Modifier.height(8.dp))
                DropdownField(
                    label = "ماه",
                    items = (1..12).toList(),
                    selected = month,
                    itemLabel = { monthNames[it - 1] },
                    onSelect = { month = it }
                )
                Spacer(Modifier.height(8.dp))
                DropdownField(
                    label = "روز",
                    items = (1..31).toList(),
                    selected = day,
                    itemLabel = { Money.toPersian(it.toString()) },
                    onSelect = { day = it }
                )
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = {
                    val t = Jalali.today()
                    year = t.year; month = t.month; day = t.day
                }) { Text("امروز") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val safeDay = day.coerceAtMost(31)
                onPick(Jalali.toGregorian(Jalali.JDate(year, month, safeDay)))
            }) { Text("تأیید") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String = "تأیید",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

@Composable
fun StatusPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

fun statusColor(status: String): Color = when (status) {
    "PAID" -> AccentGreen
    "PARTIAL" -> AccentAmber
    "VOID" -> AccentRed
    "ISSUED" -> Color(0xFF60A5FA)
    else -> TextMuted
}

@Composable
fun GradientHeader(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF0B1220), Color(0xFF16213A))
                )
            )
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                if (subtitle != null) Text(subtitle, color = TextMuted, style = MaterialTheme.typography.labelMedium)
            }
            action?.invoke()
        }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String = "جست‌وجو") {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = TextMuted) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = TextMuted)
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp)
    )
}

/** انتخاب مشتری با جست‌وجو */
@Composable
fun CustomerPickerDialog(
    customers: List<ir.cmbtools.ledger.data.Customer>,
    title: String = "انتخاب مشتری",
    onDismiss: () -> Unit,
    onPick: (ir.cmbtools.ledger.data.Customer) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, customers) {
        if (query.isBlank()) customers
        else customers.filter {
            it.name.contains(query, ignoreCase = true) || it.phone.contains(query)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                SearchField(value = query, onChange = { query = it })
                Spacer(Modifier.height(8.dp))
                if (filtered.isEmpty()) {
                    EmptyState("مشتری‌ای پیدا نشد")
                } else {
                    LazyColumn(modifier = Modifier.height(260.dp)) {
                        items(filtered, key = { it.id }) { customer ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(customer) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(customer.name)
                                Text(customer.phone, color = TextMuted, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("بستن") } }
    )
}

@Composable
fun IconBadge(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier
            .size(38.dp)
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) { Text(text, color = color, fontWeight = FontWeight.Bold) }
}
