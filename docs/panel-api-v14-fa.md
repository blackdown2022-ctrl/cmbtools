# بررسی کامل API هیدی‌فای ۱۴ و اشکالاتی که «ساخت کاربر» و «همگام‌سازی» را می‌شکست

این سند نتیجهٔ بازبینی کد خودِ پنل هیدی‌فای (تگ `v14.0.5` و شاخهٔ `dev`) است، نه حدس.
مسیر فایل‌های مرجع در مخزن `hiddify/HiddifyPanel`:

```
hiddifypanel/panel/commercial/restapi/v2/admin/__init__.py     ← همهٔ مسیرها
hiddifypanel/panel/commercial/restapi/v2/admin/users_page_api.py
hiddifypanel/panel/commercial/restapi/v2/admin/users_api.py
hiddifypanel/panel/commercial/restapi/v2/admin/schema.py
hiddifypanel/panel/commercial/restapi/v2/panel/info.py
hiddifypanel/auth.py                                            ← هدر Hiddify-API-Key
```

---

## ۱) مسیرها و احراز هویت (نسخهٔ ۱۴)

| متد | مسیر | توضیح |
|-----|------|-------|
| GET | `/{proxy_path}/api/v2/panel/info/` | نسخهٔ پنل — فقط `{"version": "..."}` |
| GET | `/{proxy_path}/api/v2/admin/users/` | صفحهٔ کاربران: `{"users": [...], ...meta}` |
| POST | `/{proxy_path}/api/v2/admin/users/` | ساخت کاربر → `{"user": {...}}` |
| PATCH | `/{proxy_path}/api/v2/admin/users/<uuid>/` | ویرایش → `{"user": {...}}` |
| DELETE | `/{proxy_path}/api/v2/admin/users/<uuid>/` | حذف → `{"status": 200}` |
| POST | `/{proxy_path}/api/v2/admin/users/bulk/` | عملیات گروهی → `{"count": n}` |
| GET/POST | `/{proxy_path}/api/v2/admin/user/` | **مسیر کلاسیک**؛ آرایهٔ خالص کاربران |
| PATCH | `/{proxy_path}/api/v2/admin/user/<uuid>/` | کلاسیک، پاسخ مسطح |

احراز هویت: هدر `Hiddify-API-Key` با مقدار **UUID ادمین** (`auth.py` → `auth_before_request`).

> نکتهٔ تاریخی: `GET /admin/user/` وقتی هیچ کاربری وجود ندارد **۴۰۴** می‌دهد
> (`abort(404, "You have no user")`). کد کلاینت این ۴۰۴ را «فهرست خالی» تفسیر می‌کند، نه خطا.

---

## ۲) سه اشکالی که در اپ وجود داشت و چرا کار نمی‌کرد

### الف) پاسخ POST/PATCH در نسخهٔ ۱۴ داخل پوشش `user` است
در `users_page_api.py`:

```python
return {"user": _detail(user, _admins_map())}
```

کلاینت قبلی پاسخ را «کاربر مسطح» می‌خواند (`PanelUserDto` بدون کلید `user`) و چون فیلد
`uuid` اجباری بود، کل تبدیل JSON شکست می‌خورد. نتیجه برای کاربر:
**«اتصال هست ولی کاربر ساخته نمی‌شود»** (پیام خطا: «پاسخ پنل قابل خواندن نیست»).

**رفع:** پاسخ‌ها به‌صورت `ResponseBody` خوانده و با `PanelJson.parseUser` باز می‌شوند؛ هر دو
شکل `{"user": {...}}` و `{...}` پذیرفته می‌شود.

### ب) فیلدهای `null` در PATCH پنل را به خطا می‌انداخت
`Json { explicitNulls = true }` باعث می‌شد بدنهٔ PATCH این‌طور باشد:

```json
{"name": null, "usage_limit_GB": null, "package_days": null, "enable": true, "start_date": null}
```

و `_apply_fields` در پنل:

```python
if creating or "name" in body:
    name = str(body.get("name") or "").strip()[:512]
    if not name: abort(400, "Name is required")
...
if "usage_limit_GB" in body:
    user.usage_limit_GB = _number(body, "usage_limit_GB", ...)   # float(None) → 400
```

یعنی **هر PATCH** (تمدید، فعال/غیرفعال کردن) با ۴۰۰ رد می‌شد.

**رفع:** `explicitNulls = false` در `PanelJson.lenient`؛ فیلدهای `null` اصلاً فرستاده نمی‌شوند.

### ج) تمدید در نسخهٔ ۱۴ با پرچم انجام می‌شود، نه با مقدار
`_apply_fields` هیچ‌جای `current_usage_GB` و `start_date` را نمی‌خواند؛ فقط:

```python
if body.get("reset_days"):  user.start_date = None      # شمارش روز از اتصال بعدی
if body.get("reset_usage"): user.reset_usage()          # مصرف صفر
```

پس تمدید قبلی مصرف را صفر نمی‌کرد و روزها از تاریخ قبلی ادامه می‌یافت.

**رفع:** `renewPackage` هر چهار مورد را می‌فرستد (`reset_usage`, `reset_days`,
`current_usage_GB = 0`, `start_date: null`) تا هم نسخهٔ ۱۴ و هم نسخه‌های قدیمی درست تمدید شوند.

---

## ۳) سایر سخت‌سازی‌ها

- **تجزیهٔ آدرس پنل:** اگر کاربر کل لینک API را در فیلد «دامنه» بچسباند
  (`https://panel.example.com/AbCdEf/api/v2/admin/users/`)، خودکار به
  دامنه = `panel.example.com` و مسیر پروکسی = `AbCdEf` تبدیل می‌شود (`PanelUrl`).
- **جایگزین مسیر:** اگر `/users/` در نسخه‌ای وجود نداشت (۴۰۴/۴۰۵)، `/user/` امتحان می‌شود.
- **پیام خطای واقعی پنل:** متن `msg`/`detail` پاسخ خطا استخراج و به فارسی نمایش داده می‌شود
  (مثلاً «سقف کاربران پر است»، «نام اجباری است»).
- **عیب‌یابی درون‌برنامه‌ای:** دکمهٔ «عیب‌یابی» در تنظیمات، پنج مرحله را جدا گزارش می‌دهد:
  تنظیمات، اتصال و کلید، فهرست کاربران، دسترسی ساخت کاربر (`can_add` / `read_only`)، لینک اشتراک.
- **همگام‌سازی مشتری‌محور:** با روشن بودن «ساخت خودکار مشتری از نام کاربر پنل»، کاربران پنل
  حتی وقتی فاکتور خودکار خاموش است به‌عنوان مشتری ساخته/وصل می‌شوند و در گزارش همگام‌سازی
  تعدادشان نمایش داده می‌شود.

---

## ۴) جدول عیب‌یابی سریع

| نشانه | علت رایج | راه‌حل |
|-------|----------|--------|
| «اتصال برقرار است» ولی تعداد کاربران ۰ | متادیتا/مسیر متفاوت پنل | دکمهٔ «عیب‌یابی» را بزنید؛ مسیر درست را نشان می‌دهد |
| «مسیر پیدا نشد» | مسیر پروکسی ادمین اشتباه/شامل `/api/...` | همین مسیر را بدون `/api/v2/...` وارد کنید (اپ هم خودکار اصلاح می‌کند) |
| «دسترسی رد شد» | کلید API = UUID ادمین نیست، یا ادمین سطح دسترسی ندارد | از صفحهٔ ادمین‌ها UUID را کپی کنید |
| «سقف کاربران پر است» | `can_add = false` | در پنل سقف کاربران/ترافیک ادمین را زیاد کنید |
| کاربر ساخته می‌شود ولی در اپ نیست | همگام‌سازی خودکار خاموش است | تنظیمات → همگام‌سازی خودکار → روشن |
