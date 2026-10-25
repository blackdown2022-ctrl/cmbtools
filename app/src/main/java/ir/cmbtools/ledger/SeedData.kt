package ir.cmbtools.ledger

import ir.cmbtools.ledger.data.CustomerDao
import ir.cmbtools.ledger.data.PanelConfig
import ir.cmbtools.ledger.data.PanelDao
import ir.cmbtools.ledger.data.Plan
import ir.cmbtools.ledger.data.PlanDao

/**
 * داده‌های اولیه: بسته‌های پیش‌فرض تا اپ از بار اول قابل استفاده باشد.
 * قیمت‌ها فقط نمونه‌اند و از صفحهٔ «بسته‌ها» قابل ویرایش‌اند.
 */
class SeedData(
    private val customersDao: CustomerDao,
    private val plansDao: PlanDao,
    private val panelDao: PanelDao
) {
    suspend fun ifEmpty() {
        if (plansDao.count() == 0) {
            listOf(
                Plan(name = "یک‌ماهه ۳۰ گیگ", gb = 30, days = 30, priceRial = 1_500_000, sortOrder = 1),
                Plan(name = "یک‌ماهه ۵۰ گیگ", gb = 50, days = 30, priceRial = 2_200_000, sortOrder = 2),
                Plan(name = "دوماهه ۱۰۰ گیگ", gb = 100, days = 60, priceRial = 3_800_000, sortOrder = 3),
                Plan(name = "سه‌ماهه ۲۰۰ گیگ", gb = 200, days = 90, priceRial = 6_500_000, sortOrder = 4),
                Plan(name = "تست ۱ گیگ / ۱ روز", gb = 1, days = 1, priceRial = 0, sortOrder = 5)
            ).forEach { plansDao.insert(it) }
        }
        if (panelDao.config() == null) {
            panelDao.saveConfig(PanelConfig())
        }
    }

    suspend fun hasCustomers(): Boolean = customersDao.count() > 0
}
