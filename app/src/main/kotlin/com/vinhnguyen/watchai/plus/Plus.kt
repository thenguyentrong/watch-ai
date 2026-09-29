package com.vinhnguyen.watchai.plus

import android.app.Activity
import android.content.Context
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PackageType
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.awaitCustomerInfo
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.awaitRestore
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Buddy Plus, through RevenueCat: extras for people who want to support Buddy. Choosing your
 * Buddy's look, a thank-you mark, and new things first (Buddy on your computer is next).
 * Everything Buddy does stays free; privacy and safety are never part of it.
 *
 * RevenueCat sees an anonymous id it makes for this install and the purchases, nothing from the
 * conversations. Without a key (a build without RevenueCat) Plus isn't offered at all.
 */
class Plus(
    context: Context,
    private val apiKey: String,
    private val scope: CoroutineScope,
    debug: Boolean,
) {
    private val appContext = context.applicationContext

    /** A way to buy Plus, as the store offers it. */
    class Offer(
        val id: String,
        val title: String,
        val price: String,
        /** "month", "year", or null for a one-off. */
        val per: String?,
        /** "1 week free", when there's a trial. */
        val trial: String?,
        internal val pkg: Package,
    )

    sealed interface Bought {
        data object Done : Bought

        data object Cancelled : Bought

        data class Failed(
            val why: String,
        ) : Bought
    }

    val available: Boolean = apiKey.isNotBlank()

    private val _active = MutableStateFlow(false)

    /** True while the user has Plus. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _offers = MutableStateFlow<List<Offer>>(emptyList())
    val offers: StateFlow<List<Offer>> = _offers.asStateFlow()

    init {
        if (available) {
            if (debug) Purchases.logLevel = LogLevel.DEBUG
            Purchases.configure(PurchasesConfiguration.Builder(appContext, apiKey).build())
            Purchases.sharedInstance.updatedCustomerInfoListener = UpdatedCustomerInfoListener(::update)
            scope.launch { refresh() }
        }
    }

    /** Asks RevenueCat again: whether the user has Plus, and what's on offer. */
    suspend fun refresh() {
        if (!available) return
        try {
            update(Purchases.sharedInstance.awaitCustomerInfo())
            val current = Purchases.sharedInstance.awaitOfferings().current
            _offers.value = current?.availablePackages.orEmpty().map(::offer).sortedBy { it.per != "year" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("plus refresh failed: %s", e.javaClass.simpleName)
        }
    }

    suspend fun buy(
        activity: Activity,
        offer: Offer,
    ): Bought {
        if (!available) return Bought.Failed("Buddy Plus isn't set up in this build")
        return try {
            update(Purchases.sharedInstance.awaitPurchase(PurchaseParams.Builder(activity, offer.pkg).build()).customerInfo)
            Bought.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: PurchasesTransactionException) {
            if (e.userCancelled) Bought.Cancelled else Bought.Failed(e.error.message)
        } catch (e: Exception) {
            Bought.Failed("Something went wrong, nothing was charged")
        }
    }

    /** Brings back Plus bought before (another phone, a new install). True if there was one. */
    suspend fun restore(): Boolean {
        if (!available) return false
        return try {
            update(Purchases.sharedInstance.awaitRestore())
            _active.value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun update(info: CustomerInfo) {
        _active.value = info.entitlements[ENTITLEMENT]?.isActive == true
    }

    private fun offer(pkg: Package): Offer {
        val product = pkg.product
        val per =
            when (pkg.packageType) {
                PackageType.ANNUAL -> "year"

                PackageType.MONTHLY -> "month"

                PackageType.WEEKLY -> "week"

                PackageType.LIFETIME -> null

                else -> product.period?.let {
                    if (it.unit.name == "YEAR") {
                        "year"
                    } else if (it.unit.name == "MONTH") {
                        "month"
                    } else {
                        null
                    }
                }
            }
        val trial =
            product.defaultOption?.freePhase?.billingPeriod?.let { p ->
                val n = p.value
                val unit = p.unit.name.lowercase().removeSuffix("s")
                "$n $unit${if (n == 1) "" else "s"} free"
            }
        val title =
            when (per) {
                "year" -> "Yearly"
                "month" -> "Monthly"
                "week" -> "Weekly"
                else -> "Once, for good"
            }
        return Offer(pkg.identifier, title, product.price.formatted, per, trial, pkg)
    }

    companion object {
        /** The entitlement in RevenueCat's dashboard that Plus unlocks. */
        const val ENTITLEMENT = "plus"
    }
}
