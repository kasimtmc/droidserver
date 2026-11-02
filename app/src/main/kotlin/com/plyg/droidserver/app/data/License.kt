package com.plyg.droidserver.app.data

/** Play sürümünde tam sürümün durumu; arayüz yalnızca [Required] iken satın alma penceresine kilitlenir (sunucu çalışır). */
sealed interface License {
    /** Deneme başlangıcı henüz okunuyor (yeniden kurulumda Block Store'dan birkaç saniye). */
    data object Checking : License

    data object Owned : License

    data class Trial(val endsAt: Long) : License

    /**
     * Deneme bitti, satın alma yok. [price]: Play'in biçimlendirdiği fiyat (ürün okunamadıysa null); [pending]: ödeme
     * onay bekliyor; [error]: son Play işleminin hatası.
     */
    data class Required(val price: String? = null, val pending: Boolean = false, val error: String? = null) : License
}

/** Tek seferlik satın almanın yanındaki deneme süresi; Play'e bağlı olmayan hesap (testlerde de). */
object TrialPolicy {
    const val DAYS = 7
    const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Denemenin başladığı an: telefondaki ve Block Store'daki (yeniden kurulumdan sonra da kalır) kayıtların erkeni;
     * hiçbiri yoksa şimdi (ilk açılış ya da bu özellikten önce kurulmuş uygulamanın ilk güncel açılışı).
     */
    fun start(local: Long?, cloud: Long?, now: Long): Long = listOfNotNull(local, cloud).minOrNull() ?: now

    fun license(start: Long, now: Long, owned: Boolean, price: String? = null, pending: Boolean = false, error: String? = null): License {
        val endsAt = start + DAYS * DAY_MS
        return when {
            owned -> License.Owned
            now < endsAt -> License.Trial(endsAt)
            else -> License.Required(price, pending, error)
        }
    }

    /** Denemede kalan gün, yukarı yuvarlanmış (son gün 1). */
    fun daysLeft(trial: License.Trial, now: Long): Int = (((trial.endsAt - now).coerceAtLeast(0) + DAY_MS - 1) / DAY_MS).toInt()
}
