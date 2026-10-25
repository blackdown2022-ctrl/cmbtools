# معماری و راهنمای فنی (نسخهٔ شخصی)

نسخه: ۰٫۱٫۰ — Kotlin 2.0 / Compose / Room / minSdk 26 / targetSdk 34

## ۱) اصول طراحی

1. **دفتر ثبت‌محور و تغییرناپذیر.** هیچ مانده‌ای «ذخیره» نمی‌شود؛ مانده از جمع سطرهای دفتر می‌آید:
   `مانده = Σ(DEBIT) − Σ(CREDIT)`. ویرایش/ابطال = «ثبت معکوس»، نه دست‌زدن به گذشته.
2. **پول = `Long` ریال.** هیچ `Float/Double` در مسیر پول نیست. نمایش فارسی/تومان فقط در لایهٔ UI.
3. **آفلاین‌اول.** همهٔ امور مالی محلی و بدون شبکه کار می‌کنند؛ پنل و پیامک «افزودنی» هستند.
4. **Idempotency + بازخوانی.** ساخت اکانت اول فاکتور را می‌سازد و بعد پنل را صدا می‌زند؛ اگر پنل خطا داد،
   فاکتور می‌ماند و کاربر می‌تواند از صفحهٔ فاکتور «ساخت اکانت» را دوباره بزند.
5. **تک‌کاربره.** یک ViewModel مرکزی (`AppViewModel`)، بدون بک‌اند، بدون همگام‌سازی ابری.

## ۲) لایه‌ها

```
ui/ (Compose)  ── AppViewModel ──┬── data/ (Room: DAO + Repository)
                                 ├── panel/ (Retrofit → Hiddify v2)
                                 ├── sms/   (Receiver + Parser + Matcher + Sender)
                                 └── export/ (CSV/JSON + FileProvider)
```

- **core/Money**: پارس/قالب‌بندی ارقام فارسی و عربی، تومان↔ریال.
- **core/Jalali**: تبدیل دقیق میلادی↔شمسی (الگوریتم jalaali-js)، قالب‌بندی فارسی، پارس ورودی.
- **core/Prefs**: `EncryptedSharedPreferences` (AES-256-GCM، کلید در Keystore) برای کلید API پنل،
  کلید درگاه پیامک، قالب پیامک و رمز قفل.

## ۳) مدل داده (Room، نسخه ۱)

| جدول | نقش |
|------|------|
| `customers` | مشتری، موبایل، ۴ رقم کارت، سقف اعتبار |
| `plans` | بسته‌ها (گیگ/روز/قیمت) |
| `invoices` / `invoice_lines` | فاکتور و ردیف‌ها + `panelUserUuid` + `subscriptionUrl` |
| `payments` / `payment_allocations` | پرداخت و تخصیص چندفاکتوری (FIFO) |
| `ledger_entries` | دفتر: DEBIT/CREDIT با `refType` و `refId` |
| `expenses` | هزینه‌ها (سرور/پهنا/پیامک) برای سود واقعی |
| `panel_configs` / `panel_accounts` | اتصال پنل و اکانت‌های همگام‌شده |
| `bank_sms` | پیامک خام + مبلغ/بانک/کارت + وضعیت تطبیق (`rawHash` یکتا) |
| `sms_outbox` | سابقهٔ ارسال پیامک و خطاها |

مهاجرت‌ها: فعلاً `fallbackToDestructiveMigration` (نسخهٔ ۱). **قبل از انتشار نسخهٔ بعدی،
Migration واقعی بنویسید** وگرنه داده پاک می‌شود.

## ۴) جریان صدور فاکتور + اکانت

```
createInvoice (Room transaction-ish)
   ├─ شمارهٔ فاکتور: <سال شمسی>-<seq>  (داخل Mutex، ضد کلیک دوباره)
   ├─ ثبت DEBIT در ledger (account=RECEIVABLE)
   └─ اکانت؟ → PanelService.createAccount
                 ├─ POST /admin/users/  (name + invoiceNo، comment=cmbtools:<id>:<name>)
                 ├─ linkBase از meta.link_domains[0].base
                 ├─ subscriptionUrl = linkBase + uuid + "/"
                 └─ invoices.setPanelInfo(uuid, subscriptionUrl)
   └─ پیامک؟ → SmsTemplate.render → SmsSender (GATEWAY یا SIM) → sms_outbox
```

**تمدید** دو شکل دارد:

| هدف | عملیات | معادل پنل |
|-----|--------|-----------|
| دورهٔ جدید (ریست مصرف، شمارش روز از اتصال بعدی) | `PATCH /users/{uuid}` با `start_date=null`, `current_usage_GB=0`, `package_days`, `usage_limit_GB`, `enable=true` | دکمهٔ تمدید پنل |
| افزودن حجم/روز روی بستهٔ فعلی | `POST /users/bulk` با `action=add_limits` | همان رفتار صفحهٔ کاربران |

## ۵) موتور تطبیق پیامک بانکی

```
SMS_RECEIVED → ادغام بخش‌ها → BankSmsParser
   ├─ نرمال‌سازی ارقام (فارسی/عربی) و «ي/ك»
   ├─ تشخیص OTP/برداشت/برگشتی → IGNORED
   ├─ استخراج مبلغ (ریال/تومان)، ۴ رقم کارت، مانده، شماره پیگیری
   └─ PaymentMatcher.handle
        ├─ تکراری؟ (sha256 روی sender+body+دقیقه) → drop
        ├─ مبلغ > آستانهٔ تنظیمات → NeedsReview
        ├─ کارت مشتری + ماندهٔ فاکتور برابر → AUTO (ثبت پرداخت + تخصیص)
        ├─ تنها یک فاکتور با همان مبلغ → پیشنهاد (تأیید دستی)
        └─ وگرنه → صف UNMATCHED برای تخصیص دستی
```

پس از تطبیق: `payments.record()` → تخصیص FIFO → `invoice.recollectTotals()` → ثبت CREDIT در دفتر.
مبلغی که فاکتور باز برایش نماند در `payment.unallocatedRial` می‌ماند (اعتبار مشتری) و گم نمی‌شود.

## ۶) گزارش‌ها

- **داشبورد**: درآمد ماه، مطالبات، اکانت‌های فعال، منقضی‌های ۷ روز آینده، پیامک‌های در انتظار.
- **فروش ۱۴ روز** (نمودار میله‌ای ساده و بدون کتابخانهٔ خارجی).
- **سنی بدهی**: ۰–۳۰ / ۳۱–۶۰ / ۶۱–۹۰ / +۹۰.
- **بدهکاران** با پرچم «سقف اعتبار رد شد».
- **سود/زیان**: دریافت‌های ماه − هزینه‌ها.
- **خروجی**: CSV با BOM (نمایش درست فارسی در Excel) و پشتیبان کامل JSON.

## ۷) امنیت

- قفل اپ: رمز ۴ رقمی (ذخیره در EncryptedSharedPreferences).
- کلید API پنل هرگز لاگ نمی‌شود (`HttpLoggingInterceptor.Level.NONE`).
- `allowBackup=false` + قواعد `data_extraction_rules` → دیتابیس به ابر بکاپ نمی‌رود.
- مجوزها: `RECEIVE_SMS`, `READ_SMS`, `SEND_SMS` (اختیاری)، `POST_NOTIFICATIONS`.

## ۸) نکات شناخته‌شده / کارهای بعدی

1. **Migration واقعی Room** قبل از نسخهٔ ۲ (الان destructive).
2. تطبیق خودکار فعلاً محافظه‌کار است؛ با افزودن نمونهٔ پیامک بانک‌های خودتان، قواعد را دقیق‌تر کنید.
3. تمدید دسته‌ای «همهٔ منقضی‌ها» هنوز UI ندارد (سرویس پنل با `add_limits` آماده است).
4. PDF فاکتور پیاده نشده؛ فعلاً متن پیامک + CSV/JSON. (اگر لازم شد، `StaticLayout` + `PdfDocument`.)
5. پشتیبانی چند پنل هم‌زمان: مدل داده `panelId` دارد، اما UI فعلاً یک پنل.
6. تست‌های UI/Compose و تست قراردادی پنل روی سرور واقعی به‌صورت دستی انجام می‌شود.
